package com.aerosync.network;

import com.aerosync.dto.graph.FlightNodeDto;
import com.aerosync.dto.graph.GraphResponse;
import com.aerosync.graph.RelationshipType;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.repository.FlightDependencyRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import com.aerosync.service.OperationalGraphService;
import com.aerosync.service.SyntheticNetworkSeeder;
import com.aerosync.service.SyntheticNetworkSeeder.SeedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seeder writes the synthetic network through JPA and never duplicates it. */
class SyntheticNetworkSeederIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired
    SyntheticNetworkSeeder seeder;

    @Autowired
    OperationalGraphService graphService;

    @Autowired
    FlightScheduleRepository flightScheduleRepository;

    @Autowired
    FlightInstanceRepository flightInstanceRepository;

    @Autowired
    FlightDependencyRepository flightDependencyRepository;

    @Test
    void seedsOnceAndMarksEverythingSynthetic() {
        SeedResult first = seeder.seed(DAY, 1, 20_261_109L);
        assertTrue(first.seeded(), first.message());
        assertEquals(15, first.airportsInserted());
        assertEquals(first.flights(), flightInstanceRepository.count());
        assertEquals(first.flights(), flightScheduleRepository.count());
        int dependencies = first.dependencies().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(dependencies, flightDependencyRepository.count());
        assertTrue(first.flights() > 300, "one service date of the synthetic timetable");

        assertTrue(flightScheduleRepository.findAll().stream().allMatch(s -> s.getDataOrigin() == DataOrigin.SYNTHETIC));
        assertTrue(flightInstanceRepository.findAll().stream().allMatch(i ->
                i.getStatus() == FlightInstanceStatus.SCHEDULED && i.getDelayMinutes() == 0
                        && i.getActualDeparture() == null && i.getEstimatedDeparture() == null),
                "only planned data, no invented real-time data");

        SeedResult second = seeder.seed(DAY, 1, 20_261_109L);
        assertFalse(second.seeded());
        assertEquals(first.flights(), flightInstanceRepository.count(), "idempotent: nothing duplicated");
    }

    @Test
    void seededNetworkIsQueryableAndFlaggedAsSynthetic() {
        seeder.seed(DAY, 1, 20_261_109L);

        GraphResponse network = graphService.getNationalNetwork(DAY, true);
        assertTrue(network.syntheticData());
        assertNotNull(network.dataNotice());
        assertEquals(15, network.summary().airportCount());
        assertTrue(network.summary().dependencyEdgesByType().get(DependencyType.AIRCRAFT_ROTATION.name()) > 0);
        assertTrue(network.summary().dependencyEdgesByType().get(DependencyType.PASSENGER_CONNECTION.name()) > 0);

        FlightNodeDto ai101 = network.nodes().stream()
                .filter(n -> n instanceof FlightNodeDto f && f.flightNumber().equals("AI101"))
                .map(n -> (FlightNodeDto) n).findFirst().orElseThrow();
        assertEquals("BOM", ai101.originAirport());
        assertEquals(DataOrigin.SYNTHETIC, ai101.dataOrigin());

        GraphResponse ai101Graph = graphService.getFlightGraph(ai101.flightInstanceId(), 1);
        assertTrue(ai101Graph.edges().stream().anyMatch(e -> e.relationship() == RelationshipType.PASSENGER_CONNECTION
                && ai101Graph.nodes().stream().anyMatch(n -> n.id().equals(e.target())
                && n instanceof FlightNodeDto f && f.flightNumber().equals("AI202"))));
        assertTrue(ai101Graph.edges().stream().anyMatch(e -> e.relationship() == RelationshipType.AIRCRAFT_ROTATION
                && ai101Graph.nodes().stream().anyMatch(n -> n.id().equals(e.target())
                && n instanceof FlightNodeDto f && f.flightNumber().equals("AI505"))));
    }
}
