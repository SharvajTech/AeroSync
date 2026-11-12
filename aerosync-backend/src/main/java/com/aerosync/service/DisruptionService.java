package com.aerosync.service;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.*;
import com.aerosync.model.Flight;
import com.aerosync.monitoring.SeverityRules;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.security.AirportScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisruptionService {

    /** {@code detection_source} of a disruption a controller reported. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** Raised by the simulate button. */
    public static final String SOURCE_SIMULATION = "SIMULATION";
    /** Raised by AeroSync itself as a consequence of an approved resolution (cascade). */
    public static final String SOURCE_SYSTEM = "SYSTEM";
    /** Raised by the disruption monitor. */
    public static final String SOURCE_MONITOR = "MONITOR";

    private final DisruptionRepository disruptionRepository;
    private final FlightRepository     flightRepository;
    private final DataSourcePolicy     dataSourcePolicy;

    // Get all disruptions (of the records served in the configured data mode)
    public List<Disruption> getAllDisruptions() {
        return visible(disruptionRepository.findAll(), AirportScope.unrestricted());
    }

    // Get active disruptions only
    public List<Disruption> getActiveDisruptions() {
        return visible(disruptionRepository.findByStatus(DisruptionStatus.DETECTED), AirportScope.unrestricted());
    }

    // ── Airport-scoped access (Stage 3) ──────────────────────────────────────
    // A disruption belongs to its flight: visible to the flight's origin and
    // destination airports, actionable by its departure airport.

    /** Disruptions of flights departing from or arriving at an airport in scope. */
    public List<Disruption> getDisruptions(AirportScope scope) {
        return visible(disruptionRepository.findAll(), scope);
    }

    public List<Disruption> getActiveDisruptions(AirportScope scope) {
        return visible(disruptionRepository.findByStatus(DisruptionStatus.DETECTED), scope);
    }

    /**
     * The disruptions a scope may see, in the configured data mode. The
     * airports stored on the disruption decide; rows written before Stage 4
     * have none and fall back to their flight in the {@code flights} table
     * (no flight: visible to system callers only).
     */
    private List<Disruption> visible(List<Disruption> disruptions, AirportScope scope) {
        if (disruptions.isEmpty() || (!scope.isUnrestricted() && scope.airports().isEmpty())) {
            return scope.isUnrestricted() ? disruptions : List.of();
        }
        Map<String, Flight> flights = null;
        List<Disruption> result = new ArrayList<>(disruptions.size());
        for (Disruption disruption : disruptions) {
            String origin = disruption.getOriginAirport();
            String destination = disruption.getDestinationAirport();
            DataOrigin dataOrigin = disruption.getDataOrigin();
            if (origin == null || dataOrigin == null) {
                if (flights == null) {
                    flights = new HashMap<>();
                    for (Flight flight : flightRepository.findAll()) {
                        if (flight.getFlightId() != null) {
                            flights.put(flight.getFlightId(), flight);
                        }
                    }
                }
                Flight flight = disruption.getFlightId() == null ? null : flights.get(disruption.getFlightId());
                if (flight != null) {
                    origin = origin != null ? origin : flight.getOrigin();
                    destination = destination != null ? destination : flight.getDestination();
                    dataOrigin = dataOrigin != null ? dataOrigin : flight.getDataOrigin();
                }
            }
            if (!dataSourcePolicy.serves(dataOrigin)) {
                continue;
            }
            if (scope.isUnrestricted() || (origin != null && scope.canView(origin, destination))) {
                result.add(disruption);
            }
        }
        return result;
    }

    /** Trigger for a user: 403 unless the flight departs from an airport in scope. */
    public Disruption triggerDisruption(String flightId, DisruptionType type, int delayMinutes, AirportScope scope) {
        Flight flight = flightRepository.findByFlightId(flightId)
                .orElseThrow(() ->
                        new RuntimeException("Flight not found: " + flightId));
        if (!dataSourcePolicy.serves(flight)) {
            throw new RuntimeException("Flight not found: " + flightId);
        }
        scope.requireOperate(flight.getOrigin(), "Flight " + flight.getFlightId());
        return triggerDisruption(flightId, type, delayMinutes, SOURCE_MANUAL);
    }

    /** Random disruption on a flight departing from an airport in scope. */
    public Disruption simulateRandom(AirportScope scope) {
        if (scope.isUnrestricted()) {
            return simulateRandom();
        }
        List<Flight> departing = scope.airports().isEmpty() ? List.of()
                : flightRepository.findByOriginIn(scope.airports()).stream()
                        .filter(dataSourcePolicy::serves).toList();
        List<Flight> candidates = departing.stream().filter(f -> "HIGH".equals(f.getRiskLevel())).toList();
        if (candidates.isEmpty()) {
            candidates = departing.stream().filter(f -> f.getStatus() == Flight.FlightStatus.SCHEDULED).toList();
        }
        if (candidates.isEmpty()) {
            throw new RuntimeException(
                    "No flights departing from your airports are available for simulation.");
        }
        Flight target = candidates.get(new Random().nextInt(candidates.size()));
        return triggerDisruption(target.getFlightId(), randomDisruptionType(target),
                30 + new Random().nextInt(151), SOURCE_SIMULATION);
    }

    // Trigger a disruption for a specific flight (system callers, e.g. the cascade step)
    public Disruption triggerDisruption(String flightId,
                                        DisruptionType type,
                                        int delayMinutes) {
        return triggerDisruption(flightId, type, delayMinutes, SOURCE_SYSTEM);
    }

    /** As above, recording who raised it ({@code SOURCE_*}). */
    public Disruption triggerDisruption(String flightId,
                                        DisruptionType type,
                                        int delayMinutes,
                                        String detectionSource) {
        Flight flight = flightRepository.findByFlightId(flightId)
                .orElseThrow(() ->
                        new RuntimeException("Flight not found: " + flightId));

        // Mark flight as disrupted
        flight.setStatus(Flight.FlightStatus.DISRUPTED);
        flightRepository.save(flight);

        // Build description based on type
        String description = buildDescription(type, flightId, delayMinutes);

        // Estimate passengers affected and cost
        int passengers = estimatePassengers(type, delayMinutes);
        double cost    = estimateCost(delayMinutes, passengers);

        Disruption disruption = Disruption.builder()
                .flightId(flightId)
                .type(type)
                .status(DisruptionStatus.DETECTED)
                .description(description)
                .delayMinutes(delayMinutes)
                .passengersAffected(passengers)
                .costImpact(cost)
                .detectedAt(LocalDateTime.now())
                // Stage 4: who raised it, how severe, and which airports it belongs to
                .detectionSource(detectionSource)
                .severity(SeverityRules.fromDelayMinutes(delayMinutes))
                .confidence(1.0)
                .dataOrigin(flight.getDataOrigin() != null ? flight.getDataOrigin() : DataOrigin.SYNTHETIC)
                .originAirport(airportCode(flight.getOrigin()))
                .destinationAirport(airportCode(flight.getDestination()))
                .build();

        Disruption saved = disruptionRepository.save(disruption);
        log.warn("🚨 Disruption triggered → Flight: {} Type: {} Delay: {}min",
                flightId, type, delayMinutes);
        return saved;
    }

    // Simulate a random disruption on a random HIGH risk flight
    // This is called by the simulation engine button on the dashboard
    public Disruption simulateRandom() {
        // Find HIGH risk flights first
        List<Flight> highRisk = flightRepository.findByRiskLevel("HIGH");

        // Fall back to any scheduled flight if no HIGH risk found
        if (highRisk.isEmpty()) {
            highRisk = flightRepository
                    .findByStatus(Flight.FlightStatus.SCHEDULED);
        }

        if (highRisk.isEmpty()) {
            throw new RuntimeException(
                    "No flights available for simulation. Seed flight data first.");
        }

        // Pick random flight
        highRisk = highRisk.stream().filter(dataSourcePolicy::serves).toList();
        if (highRisk.isEmpty()) {
            throw new RuntimeException(
                    "No flights available for simulation in data mode " + dataSourcePolicy.mode() + ".");
        }
        Flight target = highRisk.get(
                new Random().nextInt(highRisk.size()));

        // Pick random disruption type with weighted probability
        DisruptionType type = randomDisruptionType(target);

        // Random delay between 30 and 180 minutes
        int delay = 30 + new Random().nextInt(151);

        return triggerDisruption(target.getFlightId(), type, delay, SOURCE_SIMULATION);
    }

    // Mark disruption as resolved with resolution JSON
    public Disruption markResolved(Long disruptionId,
                                   String resolutionJson,
                                   long resolutionTimeMs) {
        Disruption disruption = disruptionRepository.findById(disruptionId)
                .orElseThrow(() ->
                        new RuntimeException("Disruption not found: "
                                + disruptionId));

        disruption.setStatus(DisruptionStatus.RESOLVED);
        disruption.setResolutionJson(resolutionJson);
        disruption.setResolutionTimeMs(resolutionTimeMs);
        disruption.setResolvedAt(LocalDateTime.now());

        // Mark flight back to scheduled
        flightRepository.findByFlightId(disruption.getFlightId())
                .ifPresent(f -> {
                    f.setStatus(Flight.FlightStatus.DELAYED);
                    flightRepository.save(f);
                });

        log.info("✅ Disruption {} resolved in {}ms",
                disruptionId, resolutionTimeMs);
        return disruptionRepository.save(disruption);
    }

    // ── Decision tracking (Stage 4) ───────────────────────────────────────────
    // Status stays as before (DETECTED → PROPOSED → RESOLVED); these record
    // when a proposal was made and who approved or rejected it.

    public void recordProposed(Long disruptionId) {
        disruptionRepository.findById(disruptionId).ifPresent(d -> {
            d.setProposedAt(LocalDateTime.now());
            disruptionRepository.save(d);
        });
    }

    public void recordApproved(Long disruptionId, String username) {
        disruptionRepository.findById(disruptionId).ifPresent(d -> {
            d.setApprovedAt(LocalDateTime.now());
            d.setApprovedBy(username);
            disruptionRepository.save(d);
        });
    }

    public void recordRejected(Long disruptionId, String username) {
        disruptionRepository.findById(disruptionId).ifPresent(d -> {
            d.setRejectedAt(LocalDateTime.now());
            d.setRejectedBy(username);
            d.setRejectionCount(d.getRejectionCount() == null ? 1 : d.getRejectionCount() + 1);
            disruptionRepository.save(d);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Three-letter upper-case code, or null when the flight's value is not one. */
    private static String airportCode(String value) {
        if (value == null) {
            return null;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        return code.matches("[A-Z]{3}") ? code : null;
    }

    private DisruptionType randomDisruptionType(Flight flight) {
        // Weight based on flight's actual risk factors
        List<DisruptionType> weighted = new ArrayList<>();

        if (flight.getWeatherScore() != null
                && flight.getWeatherScore() >= 2) {
            weighted.add(DisruptionType.WEATHER_DELAY);
            weighted.add(DisruptionType.WEATHER_DELAY); // higher weight
        }
        if (flight.getCrewDutyHours() != null
                && flight.getCrewDutyHours() > 10) {
            weighted.add(DisruptionType.CREW_TIMEOUT);
            weighted.add(DisruptionType.CREW_TIMEOUT);
        }
        if (flight.getAircraftAge() != null
                && flight.getAircraftAge() > 12) {
            weighted.add(DisruptionType.AIRCRAFT_FAULT);
        }
        if (flight.getGateTurnaroundMins() != null
                && flight.getGateTurnaroundMins() < 30) {
            weighted.add(DisruptionType.GATE_CONFLICT);
        }

        // Always add cascading as a fallback
        weighted.add(DisruptionType.CASCADING_DELAY);

        return weighted.get(new Random().nextInt(weighted.size()));
    }

    private String buildDescription(DisruptionType type,
                                    String flightId,
                                    int delay) {
        return switch (type) {
            case WEATHER_DELAY ->
                    "Flight " + flightId + " delayed " + delay +
                            " minutes due to adverse weather conditions at origin airport.";
            case CREW_TIMEOUT ->
                    "Flight " + flightId + " crew has exceeded duty hour limits. " +
                            "Replacement crew required before departure.";
            case AIRCRAFT_FAULT ->
                    "Flight " + flightId + " grounded for unscheduled maintenance. " +
                            "Aircraft swap required. Estimated delay: " + delay + " minutes.";
            case GATE_CONFLICT ->
                    "Flight " + flightId + " gate conflict detected. " +
                            "Assigned gate unavailable. Reassignment required.";
            case CASCADING_DELAY ->
                    "Flight " + flightId + " delayed " + delay +
                            " minutes due to late inbound aircraft causing cascading disruption.";
            case FLIGHT_DELAY ->
                    "Flight " + flightId + " is delayed " + delay + " minutes.";
            case TURNAROUND_CONFLICT ->
                    "Flight " + flightId + " cannot depart on time: its inbound aircraft arrives too late "
                            + "for the minimum turnaround. Estimated delay: " + delay + " minutes.";
            case MISSED_CONNECTION ->
                    "Connecting passengers of flight " + flightId + " are at risk of missing it "
                            + "(inbound flight delayed).";
            case AIRCRAFT_UNAVAILABLE ->
                    "The aircraft planned for flight " + flightId + " is not available.";
        };
    }

    private int estimatePassengers(DisruptionType type, int delay) {
        return switch (type) {
            case WEATHER_DELAY    -> 120 + new Random().nextInt(60);
            case CREW_TIMEOUT     -> 80  + new Random().nextInt(100);
            case AIRCRAFT_FAULT   -> 150 + new Random().nextInt(50);
            case GATE_CONFLICT    -> 60  + new Random().nextInt(80);
            case CASCADING_DELAY  -> 100 + new Random().nextInt(80);
            case FLIGHT_DELAY, TURNAROUND_CONFLICT, MISSED_CONNECTION, AIRCRAFT_UNAVAILABLE ->
                    100 + new Random().nextInt(80);
        };
    }

    private double estimateCost(int delayMinutes, int passengers) {
        // Industry average: $150/min operational + $50/passenger
        return (delayMinutes * 150.0) + (passengers * 50.0);
    }
}