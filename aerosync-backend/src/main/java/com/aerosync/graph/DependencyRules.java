package com.aerosync.graph;

import com.aerosync.model.DependencyType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Validation rules for flight-to-flight dependencies. Used by the synthetic
 * generator, the seeder and {@code FlightNetworkService} so that every stored
 * dependency is physically and temporally plausible.
 *
 * <ul>
 *   <li>All types: source and target are different flights, impact weight in
 *       [0, 1], minimum connection minutes not negative.</li>
 *   <li>AIRCRAFT_ROTATION, PASSENGER_CONNECTION, CREW_ROTATION: the target
 *       departs from the airport where the source arrives, no earlier than
 *       {@code minimum_connection_minutes} after the source's scheduled
 *       arrival and within 24 hours (a longer gap absorbs any realistic
 *       delay, so it is not an operational dependency).</li>
 *   <li>AIRCRAFT_ROTATION: when both flights have an aircraft assigned it must
 *       be the same aircraft.</li>
 *   <li>PASSENGER_CONNECTION: the target does not fly back to the source's
 *       origin.</li>
 *   <li>OPERATIONAL_CONSTRAINT: the flights share an airport and the target
 *       does not depart before the source.</li>
 * </ul>
 */
public final class DependencyRules {

    /** Upper bound for a meaningful connection/rotation gap. */
    public static final Duration MAX_CONNECTION_GAP = Duration.ofHours(24);

    private static final Set<DependencyType> STATION_CONTINUITY_TYPES = Set.of(
            DependencyType.AIRCRAFT_ROTATION,
            DependencyType.PASSENGER_CONNECTION,
            DependencyType.CREW_ROTATION);

    private DependencyRules() {
    }

    /** Minimal flight information needed to validate a dependency. */
    public record FlightLeg(
            UUID flightId,
            String flightNumber,
            String originIata,
            String destinationIata,
            Instant scheduledDeparture,
            Instant scheduledArrival,
            UUID aircraftId) {
    }

    /** Returns every rule violation (empty list = valid). */
    public static List<String> violations(FlightLeg source, FlightLeg target, DependencyType type,
                                          double impactWeight, Integer minimumConnectionMinutes) {
        List<String> violations = new ArrayList<>();
        if (source == null || target == null) {
            violations.add("source and target flights are required");
            return violations;
        }
        if (type == null) {
            violations.add("dependency type is required");
            return violations;
        }
        if (Objects.equals(source.flightId(), target.flightId())) {
            violations.add("a flight cannot depend on itself (" + describe(source) + ")");
        }
        if (Double.isNaN(impactWeight) || impactWeight < 0.0 || impactWeight > 1.0) {
            violations.add("impact_weight must be between 0 and 1, was " + impactWeight);
        }
        if (minimumConnectionMinutes != null && minimumConnectionMinutes < 0) {
            violations.add("minimum_connection_minutes must not be negative, was " + minimumConnectionMinutes);
        }
        if (source.scheduledDeparture() == null || source.scheduledArrival() == null
                || target.scheduledDeparture() == null || target.scheduledArrival() == null) {
            violations.add("both flights need scheduled departure and arrival times");
            return violations;
        }

        if (STATION_CONTINUITY_TYPES.contains(type)) {
            checkStationContinuity(source, target, type, minimumConnectionMinutes, violations);
        }
        switch (type) {
            case AIRCRAFT_ROTATION -> {
                if (source.aircraftId() != null && target.aircraftId() != null
                        && !source.aircraftId().equals(target.aircraftId())) {
                    violations.add("aircraft rotation requires the same aircraft on " + describe(source)
                            + " and " + describe(target));
                }
            }
            case PASSENGER_CONNECTION -> {
                if (Objects.equals(target.destinationIata(), source.originIata())) {
                    violations.add("passenger connection " + describe(source) + " -> " + describe(target)
                            + " returns to its origin " + source.originIata());
                }
            }
            case OPERATIONAL_CONSTRAINT -> {
                boolean sharesAirport = Objects.equals(source.originIata(), target.originIata())
                        || Objects.equals(source.originIata(), target.destinationIata())
                        || Objects.equals(source.destinationIata(), target.originIata())
                        || Objects.equals(source.destinationIata(), target.destinationIata());
                if (!sharesAirport) {
                    violations.add("operational constraint flights " + describe(source) + " and "
                            + describe(target) + " do not share an airport");
                }
                if (target.scheduledDeparture().isBefore(source.scheduledDeparture())) {
                    violations.add("operational constraint target " + describe(target)
                            + " departs before its source " + describe(source));
                }
            }
            case CREW_ROTATION -> {
                // station continuity and minimum connection already checked
            }
        }
        return violations;
    }

    /** @throws InvalidDependencyException if any rule is violated */
    public static void requireValid(FlightLeg source, FlightLeg target, DependencyType type,
                                    double impactWeight, Integer minimumConnectionMinutes) {
        List<String> violations = violations(source, target, type, impactWeight, minimumConnectionMinutes);
        if (!violations.isEmpty()) {
            throw new InvalidDependencyException(violations);
        }
    }

    /** Scheduled minutes between the source's arrival and the target's departure. */
    public static long scheduledGapMinutes(FlightLeg source, FlightLeg target) {
        return Duration.between(source.scheduledArrival(), target.scheduledDeparture()).toMinutes();
    }

    private static void checkStationContinuity(FlightLeg source, FlightLeg target, DependencyType type,
                                               Integer minimumConnectionMinutes, List<String> violations) {
        if (!Objects.equals(source.destinationIata(), target.originIata())) {
            violations.add(type + " requires " + describe(target) + " to depart from "
                    + source.destinationIata() + " where " + describe(source) + " arrives, but it departs from "
                    + target.originIata());
        }
        if (minimumConnectionMinutes == null) {
            violations.add("minimum_connection_minutes is required for " + type);
            return;
        }
        long gap = scheduledGapMinutes(source, target);
        if (gap < minimumConnectionMinutes) {
            violations.add(type + " " + describe(source) + " -> " + describe(target) + " has " + gap
                    + " scheduled minutes, below the minimum of " + minimumConnectionMinutes);
        }
        if (gap > MAX_CONNECTION_GAP.toMinutes()) {
            violations.add(type + " " + describe(source) + " -> " + describe(target) + " has a " + gap
                    + " minute gap, above the 24 hour maximum");
        }
    }

    private static String describe(FlightLeg leg) {
        return (leg.flightNumber() == null ? "flight" : leg.flightNumber()) + " (" + leg.flightId() + ")";
    }
}
