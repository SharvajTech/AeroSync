package com.aerosync.monitoring;

import com.aerosync.graph.DependencyRecord;
import com.aerosync.graph.FlightRecord;
import com.aerosync.model.DependencyType;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.FlightInstanceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Monitoring rules for operational-network flights ({@code flight_instances})
 * and their dependencies. Pure schedule arithmetic on recorded data: a delay
 * is followed along the dependency edges and reported where it no longer fits
 * the minimum connection time.
 *
 * <ul>
 *   <li>{@link #RULE_FLIGHT_DELAY}: the flight itself carries a recorded delay.</li>
 *   <li>{@link #RULE_TURNAROUND} / {@link #RULE_CREW_ROTATION}: the inbound
 *       flight of an aircraft or crew rotation arrives too late for the
 *       minimum turnaround, so the next flight cannot leave on time.</li>
 *   <li>{@link #RULE_MISSED_CONNECTION}: a passenger connection is shorter
 *       than its minimum connection time.</li>
 *   <li>{@link #RULE_CASCADE}: the same as a turnaround conflict, two or more
 *       hops away from the flight that is actually late.</li>
 *   <li>{@link #RULE_AIRCRAFT_UNAVAILABLE}: the assigned aircraft is in
 *       maintenance or grounded.</li>
 * </ul>
 *
 * <p>These detections are advisory. With the data AeroSync has today a
 * network flight only carries a delay when one was recorded on it; the
 * synthetic network is generated on time and OpenSky provides no timetable,
 * so no delay can be derived from it.</p>
 */
public final class NetworkRules {

    public static final String RULE_FLIGHT_DELAY = "NETWORK_FLIGHT_DELAY";
    public static final String RULE_TURNAROUND = "TURNAROUND_CONFLICT";
    public static final String RULE_CREW_ROTATION = "CREW_ROTATION_CONFLICT";
    public static final String RULE_MISSED_CONNECTION = "MISSED_CONNECTION";
    public static final String RULE_CASCADE = "CASCADING_DELAY";
    public static final String RULE_AIRCRAFT_UNAVAILABLE = "AIRCRAFT_UNAVAILABLE";

    /** Confidence of a conflict one hop from a recorded delay, and the factor per further hop. */
    static final double FIRST_HOP_CONFIDENCE = 0.90;
    static final double PER_HOP_FACTOR = 0.85;
    /** Flights that left up to this long ago are still reported (they are still under way). */
    static final Duration REPORT_LOOKBACK = Duration.ofHours(2);

    private static final Set<FlightInstanceStatus> NOT_AFFECTED = EnumSet.of(
            FlightInstanceStatus.DEPARTED, FlightInstanceStatus.ARRIVED, FlightInstanceStatus.CANCELLED,
            FlightInstanceStatus.DIVERTED);

    private NetworkRules() {
    }

    /** Reads the network around the late flights; implemented on the repositories. */
    public interface NetworkReader {

        /** Active dependencies whose source flight is one of {@code flightIds}. */
        List<DependencyRecord> outgoing(Set<UUID> flightIds);

        /** The flights with these ids (ids that are not served are simply missing). */
        Map<UUID, FlightRecord> flights(Set<UUID> flightIds);
    }

    /**
     * @param lateFlights            flights carrying a recorded delay or marked delayed
     * @param withUnavailableAircraft flights still to depart whose aircraft is not available
     */
    public static List<Detection> detect(Collection<FlightRecord> lateFlights,
                                         Collection<FlightRecord> withUnavailableAircraft,
                                         NetworkReader reader, Instant now, MonitoringSettings settings) {
        Map<String, Detection> detections = new LinkedHashMap<>();
        Map<UUID, FlightRecord> known = new HashMap<>();
        Map<UUID, Integer> delay = new HashMap<>();
        Map<UUID, Integer> hops = new HashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>();

        for (FlightRecord flight : lateFlights) {
            int minutes = recordedDelay(flight);
            if (minutes < settings.networkMinDelay()) {
                continue;
            }
            known.put(flight.flightId(), flight);
            delay.put(flight.flightId(), minutes);
            hops.put(flight.flightId(), 0);
            frontier.add(flight.flightId());
            if (reportable(flight, now, settings) && flight.status() != FlightInstanceStatus.ARRIVED
                    && flight.status() != FlightInstanceStatus.CANCELLED) {
                put(detections, new Detection(
                        RULE_FLIGHT_DELAY + "|fi:" + flight.flightId(), RULE_FLIGHT_DELAY,
                        DisruptionType.FLIGHT_DELAY, flight.flightNumber(), flight.flightId(),
                        flight.originIata(), flight.destinationIata(), flight.dataOrigin(),
                        SeverityRules.fromDelayMinutes(minutes), 1.0, minutes,
                        "Flight " + flight.flightNumber() + " " + flight.originIata() + "-"
                                + flight.destinationIata() + " has a recorded delay of " + minutes + " min."));
            }
        }

        for (int depth = 1; depth <= settings.maxPropagationDepth() && !frontier.isEmpty(); depth++) {
            List<DependencyRecord> dependencies = reader.outgoing(frontier);
            Set<UUID> missing = new LinkedHashSet<>();
            for (DependencyRecord dependency : dependencies) {
                if (!known.containsKey(dependency.targetFlightId())) {
                    missing.add(dependency.targetFlightId());
                }
            }
            if (!missing.isEmpty()) {
                known.putAll(reader.flights(missing));
            }

            Set<UUID> next = new LinkedHashSet<>();
            for (DependencyRecord dependency : dependencies) {
                FlightRecord source = known.get(dependency.sourceFlightId());
                FlightRecord target = known.get(dependency.targetFlightId());
                Integer sourceDelay = delay.get(dependency.sourceFlightId());
                if (source == null || target == null || sourceDelay == null || !dependency.active()
                        || dependency.minimumConnectionMinutes() == null
                        || NOT_AFFECTED.contains(target.status())) {
                    continue;
                }
                // The source is ready for the next flight after it arrives (late) and the minimum
                // connection time has passed; the target leaves at its own scheduled time plus
                // whatever delay is already recorded on it.
                Instant ready = source.scheduledArrival()
                        .plus(Duration.ofMinutes(sourceDelay))
                        .plus(Duration.ofMinutes(dependency.minimumConnectionMinutes()));
                int targetRecorded = recordedDelay(target);
                Instant departure = target.scheduledDeparture().plus(Duration.ofMinutes(targetRecorded));
                long shortfall = Duration.between(departure, ready).toMinutes();
                if (shortfall <= 0) {
                    continue;
                }
                int sourceHops = hops.getOrDefault(dependency.sourceFlightId(), 0);
                double confidence = FIRST_HOP_CONFIDENCE * Math.pow(PER_HOP_FACTOR, sourceHops);
                String via = "|fi:" + target.flightId() + "|via:" + source.flightId();
                String link = target.flightNumber() + " " + target.originIata() + "-" + target.destinationIata();

                switch (dependency.type()) {
                    case PASSENGER_CONNECTION -> {
                        if (reportable(target, now, settings)) {
                            put(detections, new Detection(RULE_MISSED_CONNECTION + via, RULE_MISSED_CONNECTION,
                                    DisruptionType.MISSED_CONNECTION, target.flightNumber(), target.flightId(),
                                    target.originIata(), target.destinationIata(), target.dataOrigin(),
                                    SeverityRules.fromDelayMinutes((int) shortfall), confidence * 0.9,
                                    (int) shortfall,
                                    "Passengers connecting from " + source.flightNumber() + " (" + sourceDelay
                                            + " min late) to " + link + " are " + shortfall
                                            + " min short of the " + dependency.minimumConnectionMinutes()
                                            + " min minimum connection time."));
                        }
                    }
                    case AIRCRAFT_ROTATION, CREW_ROTATION -> {
                        int propagated = targetRecorded + (int) shortfall;
                        boolean firstHop = sourceHops == 0;
                        boolean aircraft = dependency.type() == DependencyType.AIRCRAFT_ROTATION;
                        String rule = !firstHop ? RULE_CASCADE : aircraft ? RULE_TURNAROUND : RULE_CREW_ROTATION;
                        DisruptionType type = firstHop
                                ? DisruptionType.TURNAROUND_CONFLICT : DisruptionType.CASCADING_DELAY;
                        if (reportable(target, now, settings)) {
                            put(detections, new Detection(rule + via, rule, type, target.flightNumber(),
                                    target.flightId(), target.originIata(), target.destinationIata(),
                                    target.dataOrigin(), SeverityRules.fromDelayMinutes(propagated), confidence,
                                    propagated,
                                    link + " cannot leave on time: its inbound " + (aircraft ? "aircraft" : "crew")
                                            + " (" + source.flightNumber() + ", " + sourceDelay
                                            + " min late) needs " + dependency.minimumConnectionMinutes()
                                            + " min on the ground. Computed delay " + propagated + " min"
                                            + (firstHop ? "." : ", " + (sourceHops + 1)
                                            + " connections from the delayed flight.")));
                        }
                        if (propagated >= settings.networkMinDelay()
                                && propagated > delay.getOrDefault(target.flightId(), 0)) {
                            delay.put(target.flightId(), propagated);
                            hops.put(target.flightId(), sourceHops + 1);
                            next.add(target.flightId());
                        }
                    }
                    default -> {
                        // OPERATIONAL_CONSTRAINT: no connection time to compare against.
                    }
                }
            }
            frontier = next;
        }

        for (FlightRecord flight : withUnavailableAircraft) {
            if (!reportable(flight, now, settings)) {
                continue;
            }
            String registration = flight.aircraftRegistration() == null ? "the assigned aircraft"
                    : "aircraft " + flight.aircraftRegistration();
            put(detections, new Detection(
                    RULE_AIRCRAFT_UNAVAILABLE + "|fi:" + flight.flightId(), RULE_AIRCRAFT_UNAVAILABLE,
                    DisruptionType.AIRCRAFT_UNAVAILABLE, flight.flightNumber(), flight.flightId(),
                    flight.originIata(), flight.destinationIata(), flight.dataOrigin(),
                    DisruptionSeverity.HIGH, 0.95, 0,
                    "Flight " + flight.flightNumber() + " " + flight.originIata() + "-" + flight.destinationIata()
                            + " is planned on " + registration + ", which is recorded as not available "
                            + "(maintenance or grounded)."));
        }
        return new ArrayList<>(detections.values());
    }

    /** Recorded delay in minutes: the delay field, or estimated minus scheduled departure if that is larger. */
    static int recordedDelay(FlightRecord flight) {
        int minutes = Math.max(0, flight.delayMinutes());
        if (flight.estimatedDeparture() != null && flight.scheduledDeparture() != null) {
            long estimated = Duration.between(flight.scheduledDeparture(), flight.estimatedDeparture()).toMinutes();
            minutes = (int) Math.max(minutes, Math.min(estimated, 24 * 60));
        }
        return minutes;
    }

    /** Only flights around now are reported: recently departed up to the network lookahead. */
    private static boolean reportable(FlightRecord flight, Instant now, MonitoringSettings settings) {
        Instant departure = flight.scheduledDeparture();
        return !departure.isBefore(now.minus(REPORT_LOOKBACK))
                && !departure.isAfter(now.plus(settings.networkLookahead()));
    }

    /** Keeps the detection with the larger delay when a key occurs twice. */
    private static void put(Map<String, Detection> detections, Detection detection) {
        detections.merge(detection.detectionKey(), detection,
                (a, b) -> b.delayMinutes() > a.delayMinutes() ? b : a);
    }
}
