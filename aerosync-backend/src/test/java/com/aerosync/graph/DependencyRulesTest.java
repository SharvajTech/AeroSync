package com.aerosync.graph;

import com.aerosync.graph.DependencyRules.FlightLeg;
import com.aerosync.model.DependencyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.aerosync.graph.SampleNetwork.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyRulesTest {

    private final SampleNetwork sample = new SampleNetwork();

    private List<String> check(FlightRecord source, FlightRecord target, DependencyType type, double weight, Integer mct) {
        return DependencyRules.violations(source.toLeg(), target.toLeg(), type, weight, mct);
    }

    @Test
    void acceptsTheSampleDependencies() {
        assertTrue(check(sample.ai101, sample.ai202, DependencyType.PASSENGER_CONNECTION, 0.45, 45).isEmpty());
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.AIRCRAFT_ROTATION, 0.93, 30).isEmpty());
        assertTrue(check(sample.ai201, sample.ai202, DependencyType.AIRCRAFT_ROTATION, 0.8, 30).isEmpty());
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.CREW_ROTATION, 0.7, 40).isEmpty());
        assertTrue(check(sample.ai505, sample.ai202, DependencyType.OPERATIONAL_CONSTRAINT, 0.3, null).isEmpty());
    }

    @Test
    void rejectsSelfDependency() {
        List<String> violations = check(sample.ai101, sample.ai101, DependencyType.OPERATIONAL_CONSTRAINT, 0.3, null);
        assertTrue(violations.stream().anyMatch(v -> v.contains("cannot depend on itself")), violations.toString());
    }

    @Test
    void rejectsStationDiscontinuity() {
        // AI202 arrives in DEL; AI505 departs from BLR.
        List<String> violations = check(sample.ai202, sample.ai505, DependencyType.PASSENGER_CONNECTION, 0.4, 45);
        assertTrue(violations.stream().anyMatch(v -> v.contains("to depart from DEL")), violations.toString());
    }

    @Test
    void rejectsConnectionsShorterThanTheMinimum() {
        // AI101 arrives 08:40, AI505 departs 09:30 (50 minutes).
        List<String> violations = check(sample.ai101, sample.ai505, DependencyType.PASSENGER_CONNECTION, 0.4, 60);
        assertTrue(violations.stream().anyMatch(v -> v.contains("below the minimum of 60")), violations.toString());
    }

    @Test
    void rejectsRotationOnDifferentAircraft() {
        List<String> violations = check(sample.ai101, sample.ai202, DependencyType.AIRCRAFT_ROTATION, 0.9, 30);
        assertTrue(violations.stream().anyMatch(v -> v.contains("same aircraft")), violations.toString());
    }

    @Test
    void rejectsPassengerConnectionBackToOrigin() {
        FlightRecord backToBom = SampleNetwork.flight("AI102", "BLR", "BOM", DAY, 13, 45, 100, null, null);
        List<String> violations = check(sample.ai101, backToBom, DependencyType.PASSENGER_CONNECTION, 0.3, 45);
        assertTrue(violations.stream().anyMatch(v -> v.contains("returns to its origin")), violations.toString());
    }

    @Test
    void rejectsGapsLongerThan24Hours() {
        FlightRecord twoDaysLater = SampleNetwork.flight("AI505", "BLR", "HYD", DAY.plusDays(2), 9, 30, 70, null, null);
        List<String> violations = check(sample.ai101, twoDaysLater, DependencyType.CREW_ROTATION, 0.6, 40);
        assertTrue(violations.stream().anyMatch(v -> v.contains("24 hour maximum")), violations.toString());
    }

    @Test
    void rejectsOutOfRangeWeightsAndMissingMinimumConnection() {
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.AIRCRAFT_ROTATION, 1.2, 30).stream()
                .anyMatch(v -> v.contains("impact_weight")));
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.AIRCRAFT_ROTATION, -0.1, 30).stream()
                .anyMatch(v -> v.contains("impact_weight")));
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.AIRCRAFT_ROTATION, 0.5, null).stream()
                .anyMatch(v -> v.contains("minimum_connection_minutes is required")));
        assertTrue(check(sample.ai101, sample.ai505, DependencyType.AIRCRAFT_ROTATION, 0.5, -5).stream()
                .anyMatch(v -> v.contains("must not be negative")));
    }

    @Test
    void operationalConstraintsNeedASharedAirportAndForwardTime() {
        FlightRecord unrelated = SampleNetwork.flight("6E5001", "MAA", "CCU", DAY, 12, 0, 130, null, null);
        assertTrue(check(sample.ai101, unrelated, DependencyType.OPERATIONAL_CONSTRAINT, 0.3, null).stream()
                .anyMatch(v -> v.contains("do not share an airport")));
        assertTrue(check(sample.ai505, sample.ai101, DependencyType.OPERATIONAL_CONSTRAINT, 0.3, null).stream()
                .anyMatch(v -> v.contains("departs before its source")));
    }

    @Test
    void requireValidThrowsWithAllViolations() {
        FlightLeg source = sample.ai202.toLeg();
        FlightLeg target = new FlightLeg(UUID.randomUUID(), "AI505", "BLR", "HYD",
                sample.ai505.scheduledDeparture(), sample.ai505.scheduledArrival(), SampleNetwork.TAIL_1);
        InvalidDependencyException ex = assertThrows(InvalidDependencyException.class, () ->
                DependencyRules.requireValid(source, target, DependencyType.AIRCRAFT_ROTATION, 2.0, 30));
        assertTrue(ex.getViolations().size() >= 3, ex.getViolations().toString());
        assertEquals(ex.getViolations().size(), DependencyRules.violations(source, target,
                DependencyType.AIRCRAFT_ROTATION, 2.0, 30).size());
    }
}
