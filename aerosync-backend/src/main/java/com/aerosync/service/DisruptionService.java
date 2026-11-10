package com.aerosync.service;

import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.*;
import com.aerosync.model.Flight;
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

    private final DisruptionRepository disruptionRepository;
    private final FlightRepository     flightRepository;

    // Get all disruptions
    public List<Disruption> getAllDisruptions() {
        return disruptionRepository.findAll();
    }

    // Get active disruptions only
    public List<Disruption> getActiveDisruptions() {
        return disruptionRepository.findByStatus(DisruptionStatus.DETECTED);
    }

    // ── Airport-scoped access (Stage 3) ──────────────────────────────────────
    // A disruption belongs to its flight: visible to the flight's origin and
    // destination airports, actionable by its departure airport.

    /** Disruptions of flights departing from or arriving at an airport in scope. */
    public List<Disruption> getDisruptions(AirportScope scope) {
        if (scope.isUnrestricted()) {
            return getAllDisruptions();
        }
        List<String> flightIds = visibleFlightIds(scope);
        return flightIds.isEmpty() ? List.of() : disruptionRepository.findByFlightIdIn(flightIds);
    }

    public List<Disruption> getActiveDisruptions(AirportScope scope) {
        if (scope.isUnrestricted()) {
            return getActiveDisruptions();
        }
        List<String> flightIds = visibleFlightIds(scope);
        return flightIds.isEmpty() ? List.of()
                : disruptionRepository.findByStatusAndFlightIdIn(DisruptionStatus.DETECTED, flightIds);
    }

    /** Trigger for a user: 403 unless the flight departs from an airport in scope. */
    public Disruption triggerDisruption(String flightId, DisruptionType type, int delayMinutes, AirportScope scope) {
        Flight flight = flightRepository.findByFlightId(flightId)
                .orElseThrow(() ->
                        new RuntimeException("Flight not found: " + flightId));
        scope.requireOperate(flight.getOrigin(), "Flight " + flight.getFlightId());
        return triggerDisruption(flightId, type, delayMinutes);
    }

    /** Random disruption on a flight departing from an airport in scope. */
    public Disruption simulateRandom(AirportScope scope) {
        if (scope.isUnrestricted()) {
            return simulateRandom();
        }
        List<Flight> departing = scope.airports().isEmpty() ? List.of()
                : flightRepository.findByOriginIn(scope.airports());
        List<Flight> candidates = departing.stream().filter(f -> "HIGH".equals(f.getRiskLevel())).toList();
        if (candidates.isEmpty()) {
            candidates = departing.stream().filter(f -> f.getStatus() == Flight.FlightStatus.SCHEDULED).toList();
        }
        if (candidates.isEmpty()) {
            throw new RuntimeException(
                    "No flights departing from your airports are available for simulation.");
        }
        Flight target = candidates.get(new Random().nextInt(candidates.size()));
        return triggerDisruption(target.getFlightId(), randomDisruptionType(target), 30 + new Random().nextInt(151));
    }

    private List<String> visibleFlightIds(AirportScope scope) {
        if (scope.airports().isEmpty()) {
            return List.of();
        }
        return flightRepository.findTouchingAirports(scope.airports()).stream()
                .map(Flight::getFlightId)
                .filter(Objects::nonNull)
                .toList();
    }

    // Manually trigger a disruption for a specific flight
    public Disruption triggerDisruption(String flightId,
                                        DisruptionType type,
                                        int delayMinutes) {
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
        Flight target = highRisk.get(
                new Random().nextInt(highRisk.size()));

        // Pick random disruption type with weighted probability
        DisruptionType type = randomDisruptionType(target);

        // Random delay between 30 and 180 minutes
        int delay = 30 + new Random().nextInt(151);

        return triggerDisruption(target.getFlightId(), type, delay);
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

    // ── Private helpers ───────────────────────────────────────────────────────

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
        };
    }

    private int estimatePassengers(DisruptionType type, int delay) {
        return switch (type) {
            case WEATHER_DELAY    -> 120 + new Random().nextInt(60);
            case CREW_TIMEOUT     -> 80  + new Random().nextInt(100);
            case AIRCRAFT_FAULT   -> 150 + new Random().nextInt(50);
            case GATE_CONFLICT    -> 60  + new Random().nextInt(80);
            case CASCADING_DELAY  -> 100 + new Random().nextInt(80);
        };
    }

    private double estimateCost(int delayMinutes, int passengers) {
        // Industry average: $150/min operational + $50/passenger
        return (delayMinutes * 150.0) + (passengers * 50.0);
    }
}