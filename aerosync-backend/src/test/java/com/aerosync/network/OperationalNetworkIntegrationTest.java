package com.aerosync.network;

import com.aerosync.dto.graph.AirportNodeDto;
import com.aerosync.dto.graph.FlightNodeDto;
import com.aerosync.dto.graph.FlightReachDto;
import com.aerosync.dto.graph.GraphEdgeDto;
import com.aerosync.dto.graph.GraphNodeDto;
import com.aerosync.dto.graph.GraphResponse;
import com.aerosync.dto.operations.AirportOperationsResponse;
import com.aerosync.dto.operations.FlightMovementDto;
import com.aerosync.exception.DuplicateFlightInstanceException;
import com.aerosync.exception.InvalidReferenceException;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.graph.EdgeCategory;
import com.aerosync.graph.GraphView;
import com.aerosync.graph.InvalidDependencyException;
import com.aerosync.graph.NodeType;
import com.aerosync.graph.RelationshipType;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightDependency;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.Gate;
import com.aerosync.model.GateStatus;
import com.aerosync.repository.FlightDependencyRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import com.aerosync.repository.GateRepository;
import com.aerosync.service.AirportOperationsService;
import com.aerosync.service.FlightNetworkService.NewDependency;
import com.aerosync.service.FlightNetworkService.NewFlight;
import com.aerosync.service.OperationalGraphService;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graph construction from database records, validation and duplicate prevention (H2). */
class OperationalNetworkIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired
    OperationalGraphService graphService;

    @Autowired
    AirportOperationsService operationsService;

    @Autowired
    FlightScheduleRepository flightScheduleRepository;

    @Autowired
    FlightInstanceRepository flightInstanceRepository;

    @Autowired
    FlightDependencyRepository flightDependencyRepository;

    @Autowired
    GateRepository gateRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void createsAirportNodesFromTheAirportsTable() {
        createSampleNetwork();
        GraphResponse network = graphService.getNationalNetwork(DAY, true);

        assertEquals(GraphView.NATIONAL_NETWORK, network.view());
        assertEquals(15, network.summary().airportCount(), "national network includes every airport");
        AirportNodeDto bom = (AirportNodeDto) node(network, "airport:BOM");
        assertEquals(NodeType.AIRPORT, bom.type());
        assertEquals("Mumbai", bom.city());
        assertEquals(AirportStatus.OPERATIONAL, bom.status());
        assertEquals(airportRepository.findByIataCode("BOM").orElseThrow().getAirportId(), bom.airportId());
    }

    @Test
    void createsFlightNodesFromFlightInstances() {
        Sample sample = createSampleNetwork();
        GraphResponse network = graphService.getNationalNetwork(DAY, true);

        FlightNodeDto ai101 = (FlightNodeDto) node(network, node(sample.ai101()));
        assertEquals(NodeType.FLIGHT, ai101.type());
        assertEquals("AI101", ai101.flightNumber());
        assertEquals(DAY, ai101.operatingDate());
        assertEquals("BOM", ai101.originAirport());
        assertEquals("BLR", ai101.destinationAirport());
        assertEquals(FlightInstanceStatus.SCHEDULED, ai101.status());
        assertEquals(0, ai101.delayMinutes());
        assertEquals("VT-T01", ai101.aircraft().registration());
        assertEquals("2026-11-09T07:00:00+05:30", ai101.scheduledDepartureLocal());
        assertEquals(DataOrigin.MANUAL, ai101.dataOrigin());
        assertFalse(network.syntheticData());
        assertEquals(6, network.summary().flightCount(), "only flights operating on the requested date");
        assertTrue(network.nodes().stream().noneMatch(n -> n.id().equals(node(sample.ai101NextDay()))));
    }

    @Test
    void createsDepartsFromAndArrivesAtEdges() {
        Sample sample = createSampleNetwork();
        GraphResponse network = graphService.getNationalNetwork(DAY, true);

        GraphEdgeDto departs = edge(network, "departs_from:" + sample.ai101().getFlightId());
        assertEquals("airport:BOM", departs.source());
        assertEquals(node(sample.ai101()), departs.target());
        assertEquals(RelationshipType.DEPARTS_FROM, departs.relationship());
        GraphEdgeDto arrives = edge(network, "arrives_at:" + sample.ai101().getFlightId());
        assertEquals(node(sample.ai101()), arrives.source());
        assertEquals("airport:BLR", arrives.target());
        assertEquals(RelationshipType.ARRIVES_AT, arrives.relationship());
        assertEquals(EdgeCategory.ROUTE, arrives.category());
        assertEquals(12, network.summary().routeEdgeCount());
    }

    @Test
    void storesAircraftRotationDependencies() {
        Sample sample = createSampleNetwork();
        GraphResponse network = graphService.getNationalNetwork(DAY, true);

        List<GraphEdgeDto> rotations = network.edges().stream()
                .filter(e -> e.relationship() == RelationshipType.AIRCRAFT_ROTATION).toList();
        assertEquals(3, rotations.size());
        GraphEdgeDto ai101Rotation = rotations.stream()
                .filter(e -> e.source().equals(node(sample.ai101()))).findFirst().orElseThrow();
        assertEquals(node(sample.ai505()), ai101Rotation.target());
        assertEquals(EdgeCategory.DEPENDENCY, ai101Rotation.category());
        assertEquals(30, ai101Rotation.metadata().get("minimum_connection_minutes"));
        assertEquals("BLR", ai101Rotation.metadata().get("station"), "jsonb metadata round trip");
    }

    @Test
    void storesPassengerConnectionDependencies() {
        Sample sample = createSampleNetwork();
        FlightDependency stored = flightDependencyRepository.findActiveOutgoing(List.of(sample.ai101().getFlightId()))
                .stream().filter(d -> d.getDependencyType() == DependencyType.PASSENGER_CONNECTION)
                .findFirst().orElseThrow();
        assertEquals(sample.ai202().getFlightId(), stored.getTargetFlight().getFlightId());
        assertEquals(45, stored.getMinimumConnectionMinutes());
        assertEquals(0.45, stored.getImpactWeight(), 1e-9);
        assertEquals("BLR", stored.getMetadata().get("hub"));
        assertEquals(26, ((Number) stored.getMetadata().get("connecting_passengers")).intValue());
        assertTrue(stored.getActive());
    }

    @Test
    void verifiesTheSampleRouteAndTheSeparateAircraftRotation() {
        Sample sample = createSampleNetwork();
        GraphResponse network = graphService.getNationalNetwork(DAY, true);

        assertTrue(hasEdge(network, "airport:BOM", node(sample.ai101()), RelationshipType.DEPARTS_FROM));
        assertTrue(hasEdge(network, node(sample.ai101()), "airport:BLR", RelationshipType.ARRIVES_AT));
        assertTrue(hasEdge(network, "airport:BLR", node(sample.ai202()), RelationshipType.DEPARTS_FROM));
        assertTrue(hasEdge(network, node(sample.ai202()), "airport:DEL", RelationshipType.ARRIVES_AT));
        assertTrue(hasEdge(network, node(sample.ai101()), node(sample.ai202()), RelationshipType.PASSENGER_CONNECTION));
        assertTrue(hasEdge(network, node(sample.ai101()), node(sample.ai505()), RelationshipType.AIRCRAFT_ROTATION));
        assertFalse(hasEdge(network, node(sample.ai101()), node(sample.ai202()), RelationshipType.AIRCRAFT_ROTATION));
        assertTrue(network.edges().stream().noneMatch(e ->
                e.source().startsWith("airport:") && e.target().startsWith("airport:")));
    }

    @Test
    void returnsAnAirportWithItsConnectedFlights() {
        Sample sample = createSampleNetwork();
        GraphResponse blr = graphService.getAirportGraph("blr", DAY, true);

        assertEquals(GraphView.AIRPORT, blr.view());
        assertEquals("airport:BLR", blr.focusNodeId());
        Set<String> flights = ids(blr, NodeType.FLIGHT);
        assertEquals(Set.of(node(sample.ai101()), node(sample.ai505()), node(sample.ai506()), node(sample.ai201()),
                node(sample.ai202())), flights, "departures and arrivals at BLR only");
        assertEquals(Set.of("airport:BLR", "airport:BOM", "airport:DEL", "airport:HYD"), ids(blr, NodeType.AIRPORT));
        assertEquals(4, blr.summary().dependencyEdgeCount(), "dependencies among BLR flights are included");
    }

    @Test
    void returnsAFlightWithItsUpstreamAndDownstreamDependencies() {
        Sample sample = createSampleNetwork();

        GraphResponse ai101 = graphService.getFlightGraph(sample.ai101().getFlightId(), 1);
        assertEquals(node(sample.ai101()), ai101.focusNodeId());
        assertTrue(ai101.traversal().upstream().isEmpty());
        Map<String, DependencyType> downstream = ai101.traversal().downstream().stream()
                .collect(Collectors.toMap(FlightReachDto::flightNumber, FlightReachDto::viaDependencyType));
        assertEquals(Map.of("AI202", DependencyType.PASSENGER_CONNECTION, "AI505", DependencyType.AIRCRAFT_ROTATION),
                downstream);
        assertTrue(ai101.traversal().depthLimitReached(), "AI505 has further dependants beyond depth 1");

        GraphResponse ai202 = graphService.getFlightGraph(sample.ai202().getFlightId(), 2);
        Set<String> upstream = ai202.traversal().upstream().stream().map(FlightReachDto::flightNumber)
                .collect(Collectors.toSet());
        assertEquals(Set.of("AI101", "AI201"), upstream);
        assertTrue(ai202.traversal().downstream().isEmpty());
        assertTrue(ids(ai202, NodeType.AIRPORT).containsAll(Set.of("airport:BOM", "airport:BLR", "airport:DEL")));
    }

    @Test
    void returnsAllFlightsAffectedThroughDependencyTraversal() {
        Sample sample = createSampleNetwork();

        GraphResponse all = graphService.getAffectedFlights(sample.ai101().getFlightId(), 6, 24, null, 0.0, 500);
        assertEquals(GraphView.AFFECTED_FLIGHTS, all.view());
        Map<String, Integer> depth = all.traversal().downstream().stream()
                .collect(Collectors.toMap(FlightReachDto::flightNumber, FlightReachDto::depth));
        assertEquals(Map.of("AI202", 1, "AI505", 1, "AI506", 2), depth);
        assertNull(all.traversal().upstream());

        GraphResponse connectionsOnly = graphService.getAffectedFlights(sample.ai101().getFlightId(), 6, 24,
                EnumSet.of(DependencyType.PASSENGER_CONNECTION), 0.0, 500);
        assertEquals(List.of("AI202"), connectionsOnly.traversal().downstream().stream()
                .map(FlightReachDto::flightNumber).toList());

        GraphResponse shortHorizon = graphService.getAffectedFlights(sample.ai101().getFlightId(), 6, 3, null, 0.0, 500);
        assertEquals(List.of("AI505"), shortHorizon.traversal().downstream().stream()
                .map(FlightReachDto::flightNumber).toList());
    }

    @Test
    void rejectsInvalidAirportReferences() {
        createSampleNetwork();
        Instant departure = ist(DAY, 9, 0);
        assertThrows(InvalidReferenceException.class, () -> flightNetworkService.createFlight(new NewFlight(
                "AI900", null, "BOM", "XYZ", DAY, departure, departure.plusSeconds(3600), "A320", null, null)));
        assertThrows(InvalidReferenceException.class, () -> flightNetworkService.createFlight(new NewFlight(
                "AI901", null, "BOM", "BOM", DAY, departure, departure.plusSeconds(3600), "A320", null, null)));
        assertThrows(InvalidReferenceException.class, () -> flightNetworkService.createFlight(new NewFlight(
                "AI902", null, "BO1", "DEL", DAY, departure, departure.plusSeconds(3600), "A320", null, null)));
        assertThrows(ResourceNotFoundException.class, () -> graphService.getAirportGraph("XYZ", DAY, true));
        assertThrows(InvalidRequestParameterException.class, () -> graphService.getAirportGraph("B1", DAY, true));
        assertThrows(ResourceNotFoundException.class, () -> operationsService.getOperations("XYZ", DAY));
    }

    @Test
    void rejectsScheduleRowsThatPointAtAMissingAirport() {
        insertReferenceAirports();
        Airport bom = airportRepository.findByIataCode("BOM").orElseThrow();
        FlightSchedule schedule = FlightSchedule.builder()
                .flightNumber("AI903")
                .origin(bom)
                .destination(entityManager.getReference(Airport.class, UUID.randomUUID()))
                .scheduledDeparture(ist(DAY, 9, 0))
                .scheduledArrival(ist(DAY, 11, 0))
                .operatingAirline("AI")
                .aircraftType("A320")
                .serviceDate(DAY)
                .dataOrigin(DataOrigin.MANUAL)
                .build();
        assertThrows(DataIntegrityViolationException.class, () -> flightScheduleRepository.saveAndFlush(schedule));
    }

    @Test
    void rejectsInvalidAirportRowsThroughBeanValidation() {
        Airport invalid = Airport.builder().iataCode("BO").airportName("x").city("x").timezone("Asia/Kolkata")
                .latitude(95.0).longitude(72.0).build();
        Exception ex = assertThrows(Exception.class, () -> airportRepository.saveAndFlush(invalid));
        assertTrue(hasCause(ex, ConstraintViolationException.class), ex.toString());
    }

    @Test
    void rejectsInvalidFlightReferencesAndRuleViolations() {
        Sample sample = createSampleNetwork();
        UUID unknown = UUID.randomUUID();

        assertThrows(InvalidReferenceException.class, () -> flightNetworkService.createDependency(new NewDependency(
                unknown, sample.ai202().getFlightId(), DependencyType.PASSENGER_CONNECTION, 0.3, 45, null, null)));
        assertThrows(InvalidReferenceException.class, () -> flightNetworkService.createDependency(new NewDependency(
                sample.ai101().getFlightId(), unknown, DependencyType.PASSENGER_CONNECTION, 0.3, 45, null, null)));
        assertThrows(ResourceNotFoundException.class, () -> graphService.getFlightGraph(unknown, 1));
        assertThrows(ResourceNotFoundException.class,
                () -> graphService.getAffectedFlights(unknown, 3, 24, null, 0.0, 10));

        // AI202 arrives in DEL; AI505 departs from BLR earlier the same day.
        InvalidDependencyException invalid = assertThrows(InvalidDependencyException.class, () ->
                flightNetworkService.createDependency(new NewDependency(sample.ai202().getFlightId(),
                        sample.ai505().getFlightId(), DependencyType.PASSENGER_CONNECTION, 0.3, 45, null, null)));
        assertTrue(invalid.getViolations().size() >= 2, invalid.getViolations().toString());
        // AI101 and AI202 are flown by different aircraft, so they cannot be an aircraft rotation.
        assertThrows(InvalidDependencyException.class, () -> flightNetworkService.createDependency(new NewDependency(
                sample.ai101().getFlightId(), sample.ai202().getFlightId(), DependencyType.AIRCRAFT_ROTATION,
                0.9, 30, null, null)));
        // The same edge twice.
        assertThrows(InvalidDependencyException.class, () -> flightNetworkService.createDependency(new NewDependency(
                sample.ai101().getFlightId(), sample.ai202().getFlightId(), DependencyType.PASSENGER_CONNECTION,
                0.5, 45, null, null)));
    }

    @Test
    void rejectsSelfDependenciesBeforeTheyReachTheDatabase() {
        Sample sample = createSampleNetwork();
        FlightDependency self = FlightDependency.builder()
                .sourceFlight(sample.ai101())
                .targetFlight(sample.ai101())
                .dependencyType(DependencyType.OPERATIONAL_CONSTRAINT)
                .impactWeight(0.2)
                .metadata(Map.of())
                .active(true)
                .dataOrigin(DataOrigin.MANUAL)
                .build();
        Exception ex = assertThrows(Exception.class, () -> flightDependencyRepository.saveAndFlush(self));
        assertTrue(hasCause(ex, ConstraintViolationException.class), ex.toString());
    }

    @Test
    void preventsDuplicateFlightInstancesThroughTheService() {
        Sample sample = createSampleNetwork();
        Instant departure = ist(DAY, 7, 0);
        DuplicateFlightInstanceException ex = assertThrows(DuplicateFlightInstanceException.class, () ->
                flightNetworkService.createFlight(new NewFlight("ai101", null, "BOM", "BLR", DAY, departure,
                        departure.plusSeconds(6000), "A320", null, null)));
        assertTrue(ex.getMessage().contains("AI101"));
        assertNotEquals(sample.ai101().getFlightId(), sample.ai101NextDay().getFlightId(),
                "the same flight number on another date is a different instance");
        assertEquals(2, flightInstanceRepository.findAll().stream()
                .filter(f -> f.getSchedule().getFlightNumber().equals("AI101")).count());
    }

    @Test
    void preventsASecondInstanceForTheSameScheduleAtTheDatabaseLevel() {
        Sample sample = createSampleNetwork();
        FlightInstance duplicate = FlightInstance.builder()
                .schedule(sample.ai101().getSchedule())
                .status(FlightInstanceStatus.SCHEDULED)
                .delayMinutes(0)
                .build();
        assertThrows(DataIntegrityViolationException.class, () -> flightInstanceRepository.saveAndFlush(duplicate));
    }

    @Test
    void preventsDuplicateLegsAtTheDatabaseLevel() {
        Sample sample = createSampleNetwork();
        FlightSchedule original = sample.ai101().getSchedule();
        FlightSchedule duplicate = FlightSchedule.builder()
                .flightNumber(original.getFlightNumber())
                .origin(original.getOrigin())
                .destination(original.getDestination())
                .scheduledDeparture(original.getScheduledDeparture())
                .scheduledArrival(original.getScheduledArrival())
                .operatingAirline("AI")
                .aircraftType("A320")
                .serviceDate(original.getServiceDate())
                .dataOrigin(DataOrigin.MANUAL)
                .build();
        assertThrows(DataIntegrityViolationException.class, () -> flightScheduleRepository.saveAndFlush(duplicate));
    }

    @Test
    void rejectsServiceDatesThatDoNotMatchTheLocalDepartureDate() {
        insertReferenceAirports();
        Instant lateEvening = ist(DAY, 23, 30);
        assertThrows(InvalidRequestParameterException.class, () -> flightNetworkService.createFlight(new NewFlight(
                "AI904", null, "BOM", "DEL", DAY.plusDays(1), lateEvening, lateEvening.plusSeconds(7200),
                "A320", null, null)));
        assertThrows(InvalidRequestParameterException.class, () -> flightNetworkService.createFlight(new NewFlight(
                "AI-904", null, "BOM", "DEL", DAY, lateEvening, lateEvening.plusSeconds(7200), "A320", null, null)));
    }

    @Test
    void airportOperationsListDeparturesArrivalsAndTheAirportsGates() {
        Sample sample = createSampleNetwork();
        UUID blrId = airportRepository.findByIataCode("BLR").orElseThrow().getAirportId();
        gateRepository.saveAndFlush(Gate.builder().gateNumber("12").terminal("T2").compatibleTypes("A320, A321")
                .available(true).airportId(blrId).build());
        gateRepository.saveAndFlush(Gate.builder().gateNumber("A1").terminal("T1").available(true).build());

        AirportOperationsResponse blr = operationsService.getOperations("BLR", DAY);
        assertEquals(List.of("AI505", "AI202"), blr.departures().stream().map(FlightMovementDto::flightNumber).toList());
        assertEquals(List.of("AI101", "AI201", "AI506"), blr.arrivals().stream().map(FlightMovementDto::flightNumber).toList());
        FlightMovementDto ai101 = blr.arrivals().get(0);
        assertEquals("BOM", ai101.counterpartAirport());
        assertEquals("2026-11-09T08:40:00+05:30", ai101.scheduledTimeLocal());
        assertEquals(node(sample.ai101()), ai101.nodeId());
        assertEquals(1, blr.gates().size(), "only gates that belong to BLR");
        assertEquals(List.of("A320", "A321"), blr.gates().get(0).supportedAircraftTypes());
        assertEquals(GateStatus.OPEN, blr.gates().get(0).status(), "status defaults to OPEN");
        assertEquals(2, blr.summary().departureCount());
        assertEquals(3, blr.summary().arrivalCount());
        assertEquals(ist(DAY, 0, 0), blr.windowStart());
    }

    private static GraphNodeDto node(GraphResponse response, String id) {
        return response.nodes().stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing node " + id));
    }

    private static GraphEdgeDto edge(GraphResponse response, String id) {
        return response.edges().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing edge " + id));
    }

    private static boolean hasEdge(GraphResponse response, String source, String target, RelationshipType type) {
        return response.edges().stream().anyMatch(e ->
                e.source().equals(source) && e.target().equals(target) && e.relationship() == type);
    }

    private static Set<String> ids(GraphResponse response, NodeType type) {
        return response.nodes().stream().filter(n -> n.type() == type).map(GraphNodeDto::id).collect(Collectors.toSet());
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
