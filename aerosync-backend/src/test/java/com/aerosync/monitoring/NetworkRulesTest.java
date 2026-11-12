package com.aerosync.monitoring;

import com.aerosync.graph.DependencyRecord;
import com.aerosync.graph.FlightRecord;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.FlightInstanceStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitoring rules for operational-network flights: a recorded delay is
 * followed along rotation and connection dependencies with plain schedule
 * arithmetic.
 */
class NetworkRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-10T06:00:00Z");
    private static final MonitoringSettings SETTINGS = MonitoringSettings.defaults();

    private final Map<UUID, FlightRecord> flights = new HashMap<>();
    private final List<DependencyRecord> dependencies = new ArrayList<>();

    private final NetworkRules.NetworkReader reader = new NetworkRules.NetworkReader() {
        @Override
        public List<DependencyRecord> outgoing(Set<UUID> flightIds) {
            return dependencies.stream().filter(d -> flightIds.contains(d.sourceFlightId())).toList();
        }

        @Override
        public Map<UUID, FlightRecord> flights(Set<UUID> flightIds) {
            Map<UUID, FlightRecord> found = new HashMap<>();
            flightIds.forEach(id -> {
                if (flights.containsKey(id)) {
                    found.put(id, flights.get(id));
                }
            });
            return found;
        }
    };

    /** A flight departing {@code departInMinutes} from now with the given block time and recorded delay. */
    private FlightRecord flight(String number, String from, String to, int departInMinutes, int blockMinutes,
                                int delayMinutes, FlightInstanceStatus status) {
        Instant departure = NOW.plus(Duration.ofMinutes(departInMinutes));
        FlightRecord record = new FlightRecord(UUID.nameUUIDFromBytes(number.getBytes()), UUID.randomUUID(), number,
                LocalDate.of(2026, 11, 10), from, to, departure, departure.plus(Duration.ofMinutes(blockMinutes)),
                null, null, null, null, delayMinutes, status, number.substring(0, 2), "A320", null, "VT-ABC",
                "A320", null, DataOrigin.SYNTHETIC);
        flights.put(record.flightId(), record);
        return record;
    }

    private void depends(FlightRecord source, FlightRecord target, DependencyType type, Integer minConnection) {
        dependencies.add(new DependencyRecord(UUID.randomUUID(), source.flightId(), target.flightId(), type, 0.8,
                minConnection, true, Map.of(), source.scheduledDeparture(), target.scheduledDeparture(),
                DataOrigin.SYNTHETIC));
    }

    private List<Detection> detect(List<FlightRecord> late, List<FlightRecord> unavailable) {
        return NetworkRules.detect(late, unavailable, reader, NOW, SETTINGS);
    }

    private static Detection only(List<Detection> detections, String rule) {
        List<Detection> matches = detections.stream().filter(d -> d.rule().equals(rule)).toList();
        assertEquals(1, matches.size(), rule + " in " + detections.stream().map(Detection::rule).toList());
        return matches.get(0);
    }

    @Test
    void aRecordedDelayIsReportedOnTheFlightItself() {
        FlightRecord late = flight("AI101", "BOM", "DEL", 30, 120, 50, FlightInstanceStatus.DELAYED);

        Detection detection = only(detect(List.of(late), List.of()), NetworkRules.RULE_FLIGHT_DELAY);

        assertEquals(DisruptionType.FLIGHT_DELAY, detection.type());
        assertEquals(late.flightId(), detection.flightInstanceId());
        assertEquals("AI101", detection.flightId());
        assertEquals("BOM", detection.originAirport());
        assertEquals("DEL", detection.destinationAirport());
        assertEquals(50, detection.delayMinutes());
        assertEquals(1.0, detection.confidence());
        assertEquals(DisruptionSeverity.MEDIUM, detection.severity());
        assertEquals("NETWORK_FLIGHT_DELAY|fi:" + late.flightId(), detection.detectionKey());
    }

    @Test
    void aLateInboundAircraftThatCannotMakeItsTurnaroundIsAConflict() {
        // Inbound arrives 08:00 + 50 = 08:50, needs 35 min: ready 09:25. Outbound is due out 09:00.
        FlightRecord inbound = flight("AI101", "BOM", "DEL", 0, 120, 50, FlightInstanceStatus.DEPARTED);
        FlightRecord outbound = flight("AI102", "DEL", "BOM", 180, 120, 0, FlightInstanceStatus.SCHEDULED);
        depends(inbound, outbound, DependencyType.AIRCRAFT_ROTATION, 35);

        Detection conflict = only(detect(List.of(inbound), List.of()), NetworkRules.RULE_TURNAROUND);

        assertEquals(DisruptionType.TURNAROUND_CONFLICT, conflict.type());
        assertEquals(outbound.flightId(), conflict.flightInstanceId());
        assertEquals("DEL", conflict.originAirport());
        assertEquals(25, conflict.delayMinutes());
        assertEquals(0.9, conflict.confidence());
        assertEquals(DisruptionSeverity.LOW, conflict.severity());
        assertTrue(conflict.description().contains("35 min"), conflict.description());
    }

    @Test
    void enoughGroundTimeAbsorbsTheDelay() {
        FlightRecord inbound = flight("AI101", "BOM", "DEL", 0, 120, 20, FlightInstanceStatus.DEPARTED);
        FlightRecord outbound = flight("AI102", "DEL", "BOM", 180, 120, 0, FlightInstanceStatus.SCHEDULED);
        depends(inbound, outbound, DependencyType.AIRCRAFT_ROTATION, 35);

        List<Detection> detections = detect(List.of(inbound), List.of());

        assertFalse(detections.stream().anyMatch(d -> d.flightInstanceId().equals(outbound.flightId())));
    }

    @Test
    void aDelayCascadesAlongTheRotationWithFallingConfidence() {
        FlightRecord first = flight("AI101", "BOM", "DEL", 0, 120, 90, FlightInstanceStatus.DEPARTED);
        FlightRecord second = flight("AI102", "DEL", "BOM", 150, 120, 0, FlightInstanceStatus.SCHEDULED);
        FlightRecord third = flight("AI103", "BOM", "BLR", 300, 100, 0, FlightInstanceStatus.SCHEDULED);
        depends(first, second, DependencyType.AIRCRAFT_ROTATION, 35);
        depends(second, third, DependencyType.AIRCRAFT_ROTATION, 35);

        List<Detection> detections = detect(List.of(first), List.of());

        // first: arrives 02:00 + 90 = 03:30, ready 04:05; second due out 02:30: 95 min late.
        Detection turnaround = only(detections, NetworkRules.RULE_TURNAROUND);
        assertEquals(95, turnaround.delayMinutes());
        // second: arrives 04:30 + 95 = 06:05, ready 06:40; third due out 05:00: 100 min late.
        Detection cascade = only(detections, NetworkRules.RULE_CASCADE);
        assertEquals(DisruptionType.CASCADING_DELAY, cascade.type());
        assertEquals(third.flightId(), cascade.flightInstanceId());
        assertEquals(100, cascade.delayMinutes());
        assertEquals(0.77, cascade.confidence());            // 0.90 x 0.85
        assertEquals(DisruptionSeverity.HIGH, cascade.severity());
    }

    @Test
    void aShortPassengerConnectionIsAMissedConnectionAndDoesNotDelayTheFlight() {
        FlightRecord inbound = flight("6E201", "DEL", "BOM", 0, 120, 40, FlightInstanceStatus.DEPARTED);
        FlightRecord onward = flight("6E305", "BOM", "GOI", 165, 60, 0, FlightInstanceStatus.SCHEDULED);
        FlightRecord afterOnward = flight("6E306", "GOI", "BOM", 300, 60, 0, FlightInstanceStatus.SCHEDULED);
        depends(inbound, onward, DependencyType.PASSENGER_CONNECTION, 45);
        depends(onward, afterOnward, DependencyType.AIRCRAFT_ROTATION, 35);

        List<Detection> detections = detect(List.of(inbound), List.of());

        // Inbound arrives 02:40, passengers need 45 min: 03:25. Onward leaves 02:45: 40 min short.
        Detection missed = only(detections, NetworkRules.RULE_MISSED_CONNECTION);
        assertEquals(DisruptionType.MISSED_CONNECTION, missed.type());
        assertEquals(onward.flightId(), missed.flightInstanceId());
        assertEquals(40, missed.delayMinutes());
        // The onward flight itself is not late, so nothing propagates from it.
        assertFalse(detections.stream().anyMatch(d -> d.flightInstanceId().equals(afterOnward.flightId())));
    }

    @Test
    void finishedFlightsAndFlightsFarInTheFutureAreNotReported() {
        FlightRecord inbound = flight("AI101", "BOM", "DEL", -60, 120, 120, FlightInstanceStatus.DEPARTED);
        FlightRecord alreadyGone = flight("AI102", "DEL", "BOM", 90, 120, 0, FlightInstanceStatus.DEPARTED);
        FlightRecord cancelled = flight("AI104", "DEL", "CCU", 95, 120, 0, FlightInstanceStatus.CANCELLED);
        depends(inbound, alreadyGone, DependencyType.AIRCRAFT_ROTATION, 35);
        depends(inbound, cancelled, DependencyType.CREW_ROTATION, 35);
        FlightRecord tomorrow = flight("AI900", "BOM", "DEL", 20 * 60, 120, 45, FlightInstanceStatus.DELAYED);
        FlightRecord arrived = flight("AI050", "BOM", "DEL", -100, 60, 30, FlightInstanceStatus.ARRIVED);
        FlightRecord smallDelay = flight("AI051", "BOM", "DEL", 30, 60, 10, FlightInstanceStatus.SCHEDULED);

        List<Detection> detections = detect(List.of(inbound, tomorrow, arrived, smallDelay), List.of());

        assertEquals(List.of("AI101"), detections.stream().map(Detection::flightId).toList());
    }

    @Test
    void aFlightPlannedOnAnUnavailableAircraftIsFlagged() {
        FlightRecord flight = flight("SG8001", "BOM", "DEL", 120, 120, 0, FlightInstanceStatus.SCHEDULED);

        Detection detection = only(detect(List.of(), List.of(flight)), NetworkRules.RULE_AIRCRAFT_UNAVAILABLE);

        assertEquals(DisruptionType.AIRCRAFT_UNAVAILABLE, detection.type());
        assertEquals(DisruptionSeverity.HIGH, detection.severity());
        assertTrue(detection.description().contains("VT-ABC"), detection.description());
        assertEquals("AIRCRAFT_UNAVAILABLE|fi:" + flight.flightId(), detection.detectionKey());
    }

    @Test
    void anEstimatedDepartureCountsAsARecordedDelay() {
        FlightRecord base = flight("AI101", "BOM", "DEL", 30, 120, 0, FlightInstanceStatus.DELAYED);
        FlightRecord withEstimate = new FlightRecord(base.flightId(), base.scheduleId(), base.flightNumber(),
                base.serviceDate(), base.originIata(), base.destinationIata(), base.scheduledDeparture(),
                base.scheduledArrival(), base.scheduledDeparture().plus(Duration.ofMinutes(70)), null, null, null,
                0, FlightInstanceStatus.DELAYED, "AI", "A320", null, null, null, null, DataOrigin.MANUAL);

        assertEquals(70, NetworkRules.recordedDelay(withEstimate));
        assertEquals(70, only(detect(List.of(withEstimate), List.of()), NetworkRules.RULE_FLIGHT_DELAY)
                .delayMinutes());
    }
}
