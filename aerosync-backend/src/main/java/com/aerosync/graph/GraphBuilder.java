package com.aerosync.graph;

import com.aerosync.dto.graph.AircraftInfoDto;
import com.aerosync.dto.graph.AirportNodeDto;
import com.aerosync.dto.graph.FlightNodeDto;
import com.aerosync.dto.graph.GraphEdgeDto;
import com.aerosync.dto.graph.GraphNodeDto;
import com.aerosync.dto.graph.GraphSummaryDto;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Assembles the hybrid airport + flight graph from plain records.
 *
 * <p>Airports and flights are first-class nodes. Every flight gets exactly two
 * ROUTE edges: {@code airport:<origin> -DEPARTS_FROM-> flight:<id>} and
 * {@code flight:<id> -ARRIVES_AT-> airport:<destination>}. Operational
 * dependencies are separate DEPENDENCY edges between two flight nodes. No
 * airport-to-airport edges are created, so the network only contains routes
 * that are actually flown.</p>
 *
 * <p>Output order is deterministic: airports by IATA code, flights by
 * scheduled departure then flight number, route edges in flight order,
 * dependency edges by type, source departure and id.</p>
 */
public final class GraphBuilder {

    private static final DateTimeFormatter LOCAL_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final Comparator<FlightRecord> FLIGHT_ORDER = Comparator
            .comparing(FlightRecord::scheduledDeparture)
            .thenComparing(FlightRecord::flightNumber)
            .thenComparing(FlightRecord::flightId);

    private final Map<String, AirportRecord> airportsByIata = new TreeMap<>();
    private final Set<String> includedAirports = new LinkedHashSet<>();
    private final Map<UUID, FlightRecord> flights = new LinkedHashMap<>();
    private final Map<UUID, DependencyRecord> dependencies = new LinkedHashMap<>();
    private int omittedDependencies;

    /** Makes an airport known so flights can reference it. Not emitted unless referenced or included. */
    public GraphBuilder registerAirport(AirportRecord airport) {
        String iata = NetworkIdentifiers.normalizeIata(airport.iataCode());
        AirportRecord existing = airportsByIata.putIfAbsent(iata, airport);
        if (existing != null && !Objects.equals(existing.airportId(), airport.airportId())) {
            throw new GraphIntegrityException("Two airports share IATA code " + iata);
        }
        return this;
    }

    /** Registers the airport and always emits it as a node (even without flights). */
    public GraphBuilder addAirport(AirportRecord airport) {
        registerAirport(airport);
        includedAirports.add(NetworkIdentifiers.normalizeIata(airport.iataCode()));
        return this;
    }

    /** Emits an already registered airport as a node. */
    public GraphBuilder includeAirport(String iataCode) {
        String iata = NetworkIdentifiers.normalizeIata(iataCode);
        if (!airportsByIata.containsKey(iata)) {
            throw new GraphIntegrityException("Airport " + iata + " is not registered");
        }
        includedAirports.add(iata);
        return this;
    }

    /**
     * Adds a flight node with its DEPARTS_FROM and ARRIVES_AT edges.
     *
     * @throws GraphIntegrityException if an endpoint airport is unknown or the flight is inconsistent
     */
    public GraphBuilder addFlight(FlightRecord flight) {
        if (flight.flightId() == null) {
            throw new GraphIntegrityException("Flight " + flight.flightNumber() + " has no instance id");
        }
        requireKnownAirport(flight, flight.originIata(), "origin");
        requireKnownAirport(flight, flight.destinationIata(), "destination");
        if (flight.originIata().equals(flight.destinationIata())) {
            throw new GraphIntegrityException("Flight " + describe(flight) + " departs from and arrives at "
                    + flight.originIata());
        }
        if (flight.scheduledDeparture() == null || flight.scheduledArrival() == null
                || !flight.scheduledArrival().isAfter(flight.scheduledDeparture())) {
            throw new GraphIntegrityException("Flight " + describe(flight)
                    + " must have a scheduled arrival after its scheduled departure");
        }
        flights.putIfAbsent(flight.flightId(), flight);
        return this;
    }

    public GraphBuilder addFlights(Iterable<FlightRecord> records) {
        records.forEach(this::addFlight);
        return this;
    }

    public boolean containsFlight(UUID flightId) {
        return flights.containsKey(flightId);
    }

    /**
     * Adds a DEPENDENCY edge when both endpoint flights are part of the graph.
     * Inactive dependencies are ignored; dependencies with an endpoint outside
     * the graph are counted in {@code omittedDependencyCount}.
     *
     * @return true if the edge was added
     */
    public boolean addDependency(DependencyRecord dependency) {
        if (!dependency.active()) {
            return false;
        }
        if (dependencies.containsKey(dependency.dependencyId())) {
            return true;
        }
        if (!flights.containsKey(dependency.sourceFlightId()) || !flights.containsKey(dependency.targetFlightId())) {
            omittedDependencies++;
            return false;
        }
        dependencies.put(dependency.dependencyId(), dependency);
        return true;
    }

    public GraphBuilder addDependencies(Iterable<DependencyRecord> records) {
        records.forEach(this::addDependency);
        return this;
    }

    public OperationalGraph build() {
        List<FlightRecord> orderedFlights = new ArrayList<>(flights.values());
        orderedFlights.sort(FLIGHT_ORDER);

        Set<String> emittedAirports = new LinkedHashSet<>(includedAirports);
        for (FlightRecord flight : orderedFlights) {
            emittedAirports.add(flight.originIata());
            emittedAirports.add(flight.destinationIata());
        }

        List<GraphNodeDto> nodes = new ArrayList<>();
        airportsByIata.forEach((iata, airport) -> {
            if (emittedAirports.contains(iata)) {
                nodes.add(toAirportNode(iata, airport));
            }
        });
        for (FlightRecord flight : orderedFlights) {
            nodes.add(toFlightNode(flight));
        }

        List<GraphEdgeDto> edges = new ArrayList<>();
        for (FlightRecord flight : orderedFlights) {
            String flightNode = NetworkIdentifiers.flightNodeId(flight.flightId());
            edges.add(new GraphEdgeDto(
                    NetworkIdentifiers.departsFromEdgeId(flight.flightId()),
                    NetworkIdentifiers.airportNodeId(flight.originIata()),
                    flightNode,
                    RelationshipType.DEPARTS_FROM,
                    EdgeCategory.ROUTE,
                    routeMetadata(flight.scheduledDeparture())));
            edges.add(new GraphEdgeDto(
                    NetworkIdentifiers.arrivesAtEdgeId(flight.flightId()),
                    flightNode,
                    NetworkIdentifiers.airportNodeId(flight.destinationIata()),
                    RelationshipType.ARRIVES_AT,
                    EdgeCategory.ROUTE,
                    routeMetadata(flight.scheduledArrival())));
        }
        int routeEdges = edges.size();

        List<DependencyRecord> orderedDependencies = new ArrayList<>(dependencies.values());
        orderedDependencies.sort(Comparator
                .comparing(DependencyRecord::type)
                .thenComparing(d -> flights.get(d.sourceFlightId()).scheduledDeparture())
                .thenComparing(DependencyRecord::dependencyId));
        Map<DependencyType, Integer> byType = new EnumMap<>(DependencyType.class);
        for (DependencyType type : DependencyType.values()) {
            byType.put(type, 0);
        }
        for (DependencyRecord dependency : orderedDependencies) {
            edges.add(toDependencyEdge(dependency));
            byType.merge(dependency.type(), 1, Integer::sum);
        }

        Map<String, Integer> byTypeName = new LinkedHashMap<>();
        byType.forEach((type, count) -> byTypeName.put(type.name(), count));

        GraphSummaryDto summary = new GraphSummaryDto(
                emittedAirportCount(nodes),
                orderedFlights.size(),
                routeEdges,
                orderedDependencies.size(),
                byTypeName,
                omittedDependencies);

        boolean synthetic = orderedFlights.stream().anyMatch(f -> f.dataOrigin() == DataOrigin.SYNTHETIC)
                || orderedDependencies.stream().anyMatch(d -> d.dataOrigin() == DataOrigin.SYNTHETIC);
        return new OperationalGraph(nodes, edges, summary, synthetic);
    }

    /** Node DTO for a single flight, using the registered airports' time zones. */
    public FlightNodeDto toFlightNode(FlightRecord flight) {
        AircraftInfoDto aircraft = new AircraftInfoDto(
                flight.aircraftId(),
                flight.aircraftRegistration(),
                flight.aircraftType() != null ? flight.aircraftType() : flight.scheduledAircraftType());
        return new FlightNodeDto(
                NetworkIdentifiers.flightNodeId(flight.flightId()),
                NodeType.FLIGHT,
                flight.flightId(),
                flight.scheduleId(),
                flight.flightNumber(),
                flight.operatingAirline(),
                flight.serviceDate(),
                flight.originIata(),
                flight.destinationIata(),
                flight.scheduledDeparture(),
                flight.scheduledArrival(),
                localTime(flight.scheduledDeparture(), flight.originIata()),
                localTime(flight.scheduledArrival(), flight.destinationIata()),
                flight.estimatedDeparture(),
                flight.estimatedArrival(),
                flight.actualDeparture(),
                flight.actualArrival(),
                flight.status(),
                flight.delayMinutes(),
                aircraft,
                flight.assignedGate(),
                flight.dataOrigin(),
                flight.provenance(),
                null);
    }

    private static int emittedAirportCount(List<GraphNodeDto> nodes) {
        return (int) nodes.stream().filter(n -> n.type() == NodeType.AIRPORT).count();
    }

    private static AirportNodeDto toAirportNode(String iata, AirportRecord airport) {
        return new AirportNodeDto(
                NetworkIdentifiers.airportNodeId(iata),
                NodeType.AIRPORT,
                airport.airportId(),
                iata,
                airport.airportName(),
                airport.city(),
                airport.timezone(),
                airport.latitude(),
                airport.longitude(),
                airport.status());
    }

    private static GraphEdgeDto toDependencyEdge(DependencyRecord dependency) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dependency_id", dependency.dependencyId());
        metadata.put("dependency_type", dependency.type().name());
        metadata.put("impact_weight", dependency.impactWeight());
        metadata.put("minimum_connection_minutes", dependency.minimumConnectionMinutes());
        metadata.put("active", dependency.active());
        metadata.put("data_origin", dependency.dataOrigin() == null ? null : dependency.dataOrigin().name());
        // Stored attributes never override the core dependency fields above.
        dependency.metadata().forEach(metadata::putIfAbsent);
        RelationshipType relationship = RelationshipType.of(dependency.type());
        return new GraphEdgeDto(
                NetworkIdentifiers.dependencyEdgeId(dependency.dependencyId()),
                NetworkIdentifiers.flightNodeId(dependency.sourceFlightId()),
                NetworkIdentifiers.flightNodeId(dependency.targetFlightId()),
                relationship,
                relationship.category(),
                metadata);
    }

    private static Map<String, Object> routeMetadata(Instant scheduledTime) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scheduled_time", scheduledTime.toString());
        return metadata;
    }

    private String localTime(Instant instant, String iata) {
        if (instant == null) {
            return null;
        }
        AirportRecord airport = airportsByIata.get(iata);
        try {
            return LOCAL_TIME_FORMAT.format(instant.atZone(ZoneId.of(airport.timezone())));
        } catch (DateTimeException ex) {
            throw new GraphIntegrityException("Airport " + iata + " has an invalid time zone '"
                    + airport.timezone() + "'");
        }
    }

    private void requireKnownAirport(FlightRecord flight, String iata, String role) {
        if (iata == null || !airportsByIata.containsKey(iata)) {
            throw new GraphIntegrityException("Flight " + describe(flight) + " references unknown " + role
                    + " airport " + iata);
        }
    }

    private static String describe(FlightRecord flight) {
        return flight.flightNumber() + " (" + flight.flightId() + ")";
    }
}
