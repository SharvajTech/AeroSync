package com.aerosync.graph;

import com.aerosync.dto.graph.AirportNodeDto;
import com.aerosync.dto.graph.FlightNodeDto;
import com.aerosync.dto.graph.GraphEdgeDto;
import com.aerosync.dto.graph.GraphNodeDto;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightInstanceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aerosync.graph.SampleNetwork.DAY;
import static com.aerosync.graph.SampleNetwork.TAIL_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBuilderTest {

    private final SampleNetwork sample = new SampleNetwork();

    @Test
    void createsAirportNodeWithStableIataBasedId() {
        OperationalGraph graph = new GraphBuilder().addAirport(SampleNetwork.airport("BOM")).build();

        AirportNodeDto bom = (AirportNodeDto) graph.node("airport:BOM").orElseThrow();
        assertEquals(NodeType.AIRPORT, bom.type());
        assertEquals("BOM", bom.iataCode());
        assertEquals("Chhatrapati Shivaji Maharaj International Airport", bom.airportName());
        assertEquals("Mumbai", bom.city());
        assertEquals("Asia/Kolkata", bom.timezone());
        assertEquals(19.0896, bom.latitude(), 1e-9);
        assertEquals(72.8656, bom.longitude(), 1e-9);
        assertEquals(AirportStatus.OPERATIONAL, bom.status());
        assertEquals(1, graph.summary().airportCount());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void createsFlightNodeKeyedByFlightInstanceNotFlightNumber() {
        OperationalGraph graph = sample.builder().build();

        FlightNodeDto ai101 = (FlightNodeDto) graph.node("flight:" + sample.ai101.flightId()).orElseThrow();
        assertEquals(NodeType.FLIGHT, ai101.type());
        assertEquals(sample.ai101.flightId(), ai101.flightInstanceId());
        assertEquals("AI101", ai101.flightNumber());
        assertEquals(DAY, ai101.operatingDate());
        assertEquals("BOM", ai101.originAirport());
        assertEquals("BLR", ai101.destinationAirport());
        assertEquals(FlightInstanceStatus.SCHEDULED, ai101.status());
        assertEquals(0, ai101.delayMinutes());
        assertEquals(TAIL_1, ai101.aircraft().aircraftId());
        assertEquals("VT-T01", ai101.aircraft().registration());
        assertEquals("A320", ai101.aircraft().aircraftType());
        assertEquals("2026-11-09T07:00:00+05:30", ai101.scheduledDepartureLocal());
        assertEquals("2026-11-09T08:40:00+05:30", ai101.scheduledArrivalLocal());
        assertEquals("2026-11-09T01:30:00Z", ai101.scheduledDeparture().toString());
        assertTrue(graph.node("flight:AI101").isEmpty(), "flight numbers must never be node ids");
    }

    @Test
    void createsDepartsFromAndArrivesAtRouteEdges() {
        OperationalGraph graph = sample.builder().build();
        String ai101 = "flight:" + sample.ai101.flightId();

        GraphEdgeDto departs = graph.edge("departs_from:" + sample.ai101.flightId()).orElseThrow();
        assertEquals("airport:BOM", departs.source());
        assertEquals(ai101, departs.target());
        assertEquals(RelationshipType.DEPARTS_FROM, departs.relationship());
        assertEquals(EdgeCategory.ROUTE, departs.category());

        GraphEdgeDto arrives = graph.edge("arrives_at:" + sample.ai101.flightId()).orElseThrow();
        assertEquals(ai101, arrives.source());
        assertEquals("airport:BLR", arrives.target());
        assertEquals(RelationshipType.ARRIVES_AT, arrives.relationship());
        assertEquals(EdgeCategory.ROUTE, arrives.category());

        assertEquals(2 * sample.flights().size(), graph.summary().routeEdgeCount());
    }

    @Test
    void sampleRouteBomAi101BlrAi202Del() {
        OperationalGraph graph = sample.builder().build();
        String ai101 = "flight:" + sample.ai101.flightId();
        String ai202 = "flight:" + sample.ai202.flightId();

        assertTrue(hasEdge(graph, "airport:BOM", ai101, RelationshipType.DEPARTS_FROM));
        assertTrue(hasEdge(graph, ai101, "airport:BLR", RelationshipType.ARRIVES_AT));
        assertTrue(hasEdge(graph, "airport:BLR", ai202, RelationshipType.DEPARTS_FROM));
        assertTrue(hasEdge(graph, ai202, "airport:DEL", RelationshipType.ARRIVES_AT));
        assertTrue(hasEdge(graph, ai101, ai202, RelationshipType.PASSENGER_CONNECTION));
    }

    @Test
    void ai101HasASeparateAircraftRotationDependency() {
        OperationalGraph graph = sample.builder().build();
        String ai101 = "flight:" + sample.ai101.flightId();

        Map<RelationshipType, String> dependencies = graph.edgesFrom(ai101).stream()
                .filter(e -> e.category() == EdgeCategory.DEPENDENCY)
                .collect(Collectors.toMap(GraphEdgeDto::relationship, GraphEdgeDto::target));
        assertEquals("flight:" + sample.ai202.flightId(), dependencies.get(RelationshipType.PASSENGER_CONNECTION));
        assertEquals("flight:" + sample.ai505.flightId(), dependencies.get(RelationshipType.AIRCRAFT_ROTATION));
        assertNotEquals(dependencies.get(RelationshipType.PASSENGER_CONNECTION),
                dependencies.get(RelationshipType.AIRCRAFT_ROTATION));

        GraphEdgeDto rotation = graph.edge("dependency:" + sample.ai101ToAi505.dependencyId()).orElseThrow();
        assertEquals(0.93, (double) rotation.metadata().get("impact_weight"), 1e-9);
        assertEquals(30, rotation.metadata().get("minimum_connection_minutes"));
        assertEquals("BLR", rotation.metadata().get("station"));
        assertEquals("AIRCRAFT_ROTATION", rotation.metadata().get("dependency_type"));
    }

    @Test
    void keepsRouteAndDependencyLayersSeparateWithoutAirportToAirportEdges() {
        OperationalGraph graph = sample.builder().build();
        for (GraphEdgeDto edge : graph.edges()) {
            boolean sourceAirport = edge.source().startsWith("airport:");
            boolean targetAirport = edge.target().startsWith("airport:");
            assertFalse(sourceAirport && targetAirport, "airport-to-airport edge " + edge.id());
            if (edge.category() == EdgeCategory.ROUTE) {
                assertTrue(sourceAirport != targetAirport, "route edges connect an airport and a flight");
            } else {
                assertTrue(edge.source().startsWith("flight:") && edge.target().startsWith("flight:"));
            }
            assertEquals(edge.relationship().category(), edge.category());
        }
        assertEquals(4, graph.summary().dependencyEdgeCount());
        assertEquals(3, graph.summary().dependencyEdgesByType().get("AIRCRAFT_ROTATION"));
        assertEquals(1, graph.summary().dependencyEdgesByType().get("PASSENGER_CONNECTION"));
        assertEquals(0, graph.summary().dependencyEdgesByType().get("CREW_ROTATION"));
    }

    @Test
    void emitsOnlyReferencedAirportsUnlessIncluded() {
        OperationalGraph graph = sample.builder().build();
        Set<String> airports = graph.nodes().stream().filter(n -> n.type() == NodeType.AIRPORT)
                .map(GraphNodeDto::id).collect(Collectors.toSet());
        assertEquals(Set.of("airport:BOM", "airport:BLR", "airport:DEL", "airport:HYD"), airports);

        OperationalGraph withGoa = sample.builder().includeAirport("goi").build();
        assertTrue(withGoa.node("airport:GOI").isPresent());
    }

    @Test
    void rejectsFlightsThatReferenceUnknownAirports() {
        GraphBuilder builder = new GraphBuilder().registerAirport(SampleNetwork.airport("BOM"));
        FlightRecord toUnknown = SampleNetwork.flight("AI777", "BOM", "XYZ", DAY, 8, 0, 90, null, null);

        GraphIntegrityException ex = assertThrows(GraphIntegrityException.class, () -> builder.addFlight(toUnknown));
        assertTrue(ex.getMessage().contains("unknown destination airport XYZ"), ex.getMessage());
    }

    @Test
    void rejectsFlightsWithArrivalBeforeDeparture() {
        GraphBuilder builder = sample.builder();
        FlightRecord valid = SampleNetwork.flight("AI778", "BOM", "DEL", DAY, 8, 0, 120, null, null);
        FlightRecord broken = new FlightRecord(valid.flightId(), valid.scheduleId(), valid.flightNumber(),
                valid.serviceDate(), valid.originIata(), valid.destinationIata(), valid.scheduledArrival(),
                valid.scheduledDeparture(), null, null, null, null, 0, valid.status(), "AI", "A320",
                null, null, null, null, valid.dataOrigin());

        assertThrows(GraphIntegrityException.class, () -> builder.addFlight(broken));
    }

    @Test
    void omitsAndCountsDependenciesWhoseOtherFlightIsOutsideTheGraph() {
        GraphBuilder builder = new GraphBuilder();
        List.of("BOM", "BLR", "DEL").forEach(code -> builder.registerAirport(SampleNetwork.airport(code)));
        builder.addFlight(sample.ai101);

        assertFalse(builder.addDependency(sample.ai101ToAi202));
        OperationalGraph graph = builder.build();
        assertEquals(0, graph.summary().dependencyEdgeCount());
        assertEquals(1, graph.summary().omittedDependencyCount());
    }

    @Test
    void ignoresInactiveDependencies() {
        DependencyRecord inactive = new DependencyRecord(SampleNetwork.id("inactive"), sample.ai101.flightId(),
                sample.ai202.flightId(), DependencyType.CREW_ROTATION, 0.7, 40, false, Map.of(),
                sample.ai101.scheduledDeparture(), sample.ai202.scheduledDeparture(), null);
        GraphBuilder builder = sample.builder();

        assertFalse(builder.addDependency(inactive));
        assertEquals(0, builder.build().summary().dependencyEdgesByType().get("CREW_ROTATION"));
    }

    @Test
    void sameFlightNumberOnDifferentDatesGivesDistinctNodes() {
        FlightRecord nextDay = SampleNetwork.flight("AI101", "BOM", "BLR", DAY.plusDays(1), 7, 0, 100, TAIL_1, "VT-T01");
        OperationalGraph graph = sample.builder().addFlight(nextDay).build();

        List<FlightNodeDto> ai101 = graph.nodes().stream()
                .filter(n -> n instanceof FlightNodeDto f && f.flightNumber().equals("AI101"))
                .map(n -> (FlightNodeDto) n).toList();
        assertEquals(2, ai101.size());
        assertNotEquals(ai101.get(0).id(), ai101.get(1).id());
        assertEquals(Set.of(DAY, DAY.plusDays(1)),
                ai101.stream().map(FlightNodeDto::operatingDate).collect(Collectors.toSet()));
    }

    @Test
    void producesDeterministicOrder() {
        OperationalGraph first = sample.builder().build();
        OperationalGraph second = sample.builder().build();
        assertEquals(first.nodes(), second.nodes());
        assertEquals(first.edges(), second.edges());
        assertNull(first.node("flight:" + SampleNetwork.id("missing")).orElse(null));
        assertEquals("airport:BLR", first.nodes().get(0).id(), "airports first, ordered by IATA code");
    }

    private static boolean hasEdge(OperationalGraph graph, String source, String target, RelationshipType type) {
        return graph.edges().stream().anyMatch(e ->
                e.source().equals(source) && e.target().equals(target) && e.relationship() == type);
    }
}
