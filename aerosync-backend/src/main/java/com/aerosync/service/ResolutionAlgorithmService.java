package com.aerosync.service;

import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionAlgorithmService {

    private final FlightRepository flightRepository;
    private final GateRepository   gateRepository;
    private final CrewRepository   crewRepository;

    // ── MAIN RESOLUTION ALGORITHM ─────────────────────────────────────────────
    // Pure algorithmic resolution — no LLM involved
    // Returns a structured resolution that can be:
    // 1. Applied directly (rule-based decisions)
    // 2. Passed to LLM for explanation enrichment (hybrid mode)
    public Map<String, Object> resolveAlgorithmically(
            Disruption disruption, Flight flight) {

        log.info("🔧 Algorithm resolving disruption {} for flight {}",
                disruption.getId(), flight.getFlightId());

        Map<String, Object> resolution = new LinkedHashMap<>();
        List<String> actionsLog = new ArrayList<>();

        // Step 1 — Run DGCA rule checks
        Map<String, Object> ruleCheck = runRuleEngine(flight);
        resolution.put("rule_violations",
                ruleCheck.get("violations"));
        resolution.put("rule_status",
                ruleCheck.get("status"));

        // Step 2 — Gate resolution algorithm
        Map<String, Object> gateRes = resolveGate(
                flight, disruption.getType());
        resolution.put("gate_resolution", gateRes);
        if ("REASSIGNED".equals(gateRes.get("action"))) {
            actionsLog.add("Gate reassigned: "
                    + flight.getAssignedGate()
                    + " → " + gateRes.get("new_gate"));
            applyGateChange(flight,
                    (String) gateRes.get("new_gate"));
        }

        // Step 3 — Crew resolution algorithm
        Map<String, Object> crewRes = resolveCrew(
                flight, disruption.getType());
        resolution.put("crew_resolution", crewRes);
        if ("SWAPPED".equals(crewRes.get("action"))) {
            actionsLog.add("Crew swapped: "
                    + flight.getAssignedCrewId()
                    + " → " + crewRes.get("new_crew_id"));
            applyCrewChange(flight,
                    (String) crewRes.get("new_crew_id"));
        }

        // Step 4 — Passenger resolution algorithm
        Map<String, Object> paxRes = resolvePassengers(
                flight, disruption);
        resolution.put("passenger_resolution", paxRes);
        if (!"NONE".equals(paxRes.get("action"))) {
            actionsLog.add("Passengers: "
                    + paxRes.get("action") + " — "
                    + paxRes.get("affected_count") + " pax");
        }

        // Step 5 — Aircraft resolution algorithm
        Map<String, Object> acRes = resolveAircraft(
                flight, disruption.getType());
        resolution.put("aircraft_resolution", acRes);
        if ("SWAPPED".equals(acRes.get("action"))) {
            actionsLog.add("Aircraft swap recommended");
        }

        // Step 6 — Delay estimation algorithm
        int estimatedDelay = estimateRemainingDelay(
                flight, disruption, gateRes, crewRes);
        double costImpact = estimatedDelay * 150.0
                + (disruption.getPassengersAffected() != null
                ? disruption.getPassengersAffected() * 30.0 : 0);

        resolution.put("estimated_new_delay_mins", estimatedDelay);
        resolution.put("total_cost_impact", costImpact);
        resolution.put("actions_taken", actionsLog);
        resolution.put("severity",
                estimatedDelay > 120 ? "CRITICAL" :
                        estimatedDelay > 60  ? "HIGH" :
                        estimatedDelay > 30  ? "MEDIUM" : "LOW");
        resolution.put("confidence_score", 0.95);
        resolution.put("resolution_method", "ALGORITHMIC");
        resolution.put("algorithm_version", "2.0");

        log.info("✅ Algorithm resolved {} actions for flight {}",
                actionsLog.size(), flight.getFlightId());
        return resolution;
    }


    // ── RULE ENGINE ───────────────────────────────────────────────────────────
    private Map<String, Object> runRuleEngine(Flight flight) {
        List<Map<String, String>> violations = new ArrayList<>();
        String status = "CLEAR";

        // DGCA Rule 1 — crew duty hours
        if (flight.getCrewDutyHours() != null) {
            if (flight.getCrewDutyHours() >= 12.0) {
                violations.add(Map.of(
                        "rule",     "DGCA_MAX_DUTY",
                        "severity", "BLOCK",
                        "message",  "Crew at " + flight.getCrewDutyHours()
                                + "h — DGCA 12h maximum exceeded"
                ));
                status = "BLOCKED";
            } else if (flight.getCrewDutyHours() >= 10.0) {
                violations.add(Map.of(
                        "rule",     "DGCA_DUTY_WARNING",
                        "severity", "WARN",
                        "message",  "Crew at " + flight.getCrewDutyHours()
                                + "h — approaching DGCA limit"
                ));
                if (!"BLOCKED".equals(status)) status = "WARNING";
            }
        }

        // DGCA Rule 2 — weather severity for aircraft type
        if (flight.getWeatherScore() != null
                && flight.getWeatherScore() == 3
                && "ATR72".equals(flight.getAircraftType())) {
            violations.add(Map.of(
                    "rule",     "WEATHER_ATR72_BLOCK",
                    "severity", "BLOCK",
                    "message",  "Severe weather — ATR72 ops suspended"
            ));
            status = "BLOCKED";
        }

        // Rule 3 — gate turnaround
        if (flight.getGateTurnaroundMins() != null
                && flight.getGateTurnaroundMins() < 20) {
            violations.add(Map.of(
                    "rule",     "GATE_TURNAROUND_CRITICAL",
                    "severity", "WARN",
                    "message",  "Turnaround " + flight.getGateTurnaroundMins()
                            + " min — critically short"
            ));
            if (!"BLOCKED".equals(status)) status = "WARNING";
        }

        return Map.of(
                "status",     status,
                "violations", violations,
                "can_depart", !"BLOCKED".equals(status)
        );
    }


    // ── GATE RESOLUTION ALGORITHM ─────────────────────────────────────────────
    // Algorithm: score all available gates and pick the best one
    // Score = compatibility (must) + turnaround buffer + terminal proximity
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveGate(
            Flight flight,
            Disruption.DisruptionType type) {

        boolean needsReassignment =
                type == Disruption.DisruptionType.GATE_CONFLICT
                        || (flight.getGateTurnaroundMins() != null
                        && flight.getGateTurnaroundMins() < 30);

        if (!needsReassignment) {
            return Map.of(
                    "action",   "KEEP",
                    "new_gate", flight.getAssignedGate(),
                    "reason",   "Gate adequate for current situation",
                    "score",    100
            );
        }

        List<Gate> available = gateRepository.findByAvailableTrue();
        String aircraftType  = flight.getAircraftType() != null
                ? flight.getAircraftType() : "A320";

        // Score each available gate
        Gate bestGate  = null;
        int  bestScore = -1;

        for (Gate gate : available) {
            // Must be compatible with aircraft type
            if (gate.getCompatibleTypes() == null
                    || !gate.getCompatibleTypes()
                    .contains(aircraftType)) {
                continue;
            }
            // Must not be current gate
            if (gate.getGateNumber().equals(
                    flight.getAssignedGate())) {
                continue;
            }

            int score = 50; // base

            // Prefer same terminal
            if (flight.getAssignedGate() != null) {
                String currentTerminal = flight
                        .getAssignedGate().substring(0, 1);
                if (gate.getTerminal() != null
                        && gate.getTerminal()
                        .contains(currentTerminal)) {
                    score += 30;
                }
            }

            // Prefer wider aircraft compatibility
            // (more compatible types = more flexible gate)
            if (gate.getCompatibleTypes() != null) {
                score += gate.getCompatibleTypes()
                        .split(",").length * 5;
            }

            if (score > bestScore) {
                bestScore = score;
                bestGate  = gate;
            }
        }

        if (bestGate == null) {
            return Map.of(
                    "action",   "KEEP",
                    "new_gate", flight.getAssignedGate(),
                    "reason",   "No compatible gate available — keeping current",
                    "score",    0
            );
        }

        return Map.of(
                "action",   "REASSIGNED",
                "new_gate", bestGate.getGateNumber(),
                "terminal", bestGate.getTerminal() != null
                        ? bestGate.getTerminal() : "T1",
                "reason",   "Gate conflict/tight turnaround resolved. "
                        + "Reassigned to compatible gate "
                        + bestGate.getGateNumber(),
                "score",    bestScore
        );
    }


    // ── CREW RESOLUTION ALGORITHM ─────────────────────────────────────────────
    // Algorithm: find crew with most remaining hours + correct certification
    // Priority: duty hours remaining DESC, then certification match
    private Map<String, Object> resolveCrew(
            Flight flight,
            Disruption.DisruptionType type) {

        double crewDuty = flight.getCrewDutyHours() != null
                ? flight.getCrewDutyHours() : 0.0;

        // Swap needed if duty hours above threshold or crew timeout
        boolean needsSwap =
                crewDuty >= 10.0
                        || type == Disruption.DisruptionType.CREW_TIMEOUT;

        if (!needsSwap) {
            return Map.of(
                    "action",      "KEEP",
                    "new_crew_id", flight.getAssignedCrewId(),
                    "reason",      "Crew duty hours acceptable: "
                            + crewDuty + "/12h",
                    "hours_remaining", 12.0 - crewDuty
            );
        }

        String aircraftType = flight.getAircraftType() != null
                ? flight.getAircraftType() : "A320";

        List<Crew> available = crewRepository.findByAvailableTrue();

        // Score each available crew member
        Crew   bestCrew  = null;
        double bestScore = -1;

        for (Crew crew : available) {
            // Skip current crew
            if (crew.getCrewId().equals(
                    flight.getAssignedCrewId())) {
                continue;
            }

            // Must have certification for this aircraft
            if (crew.getCertifications() == null
                    || !crew.getCertifications()
                    .contains(aircraftType)) {
                continue;
            }

            // Must have at least 2 hours remaining (DGCA minimum)
            double remaining = crew.getMaxDutyHours() != null
                    ? crew.getMaxDutyHours() - crew.getDutyHoursUsed()
                    : 12.0 - crew.getDutyHoursUsed();

            if (remaining < 2.0) continue;

            // Score: higher remaining hours = better
            // Pilot role preferred over co-pilot for captain replacement
            double score = remaining * 10;
            if ("PILOT".equals(crew.getRole())) score += 20;

            if (score > bestScore) {
                bestScore = score;
                bestCrew  = crew;
            }
        }

        if (bestCrew == null) {
            return Map.of(
                    "action",      "KEEP",
                    "new_crew_id", flight.getAssignedCrewId(),
                    "reason",      "No qualified crew available — "
                            + "manual escalation required",
                    "hours_remaining", 12.0 - crewDuty
            );
        }

        double newRemaining = (bestCrew.getMaxDutyHours() != null
                ? bestCrew.getMaxDutyHours()
                : 12.0) - bestCrew.getDutyHoursUsed();

        return Map.of(
                "action",          "SWAPPED",
                "old_crew_id",     flight.getAssignedCrewId() != null
                        ? flight.getAssignedCrewId() : "NONE",
                "new_crew_id",     bestCrew.getCrewId(),
                "new_crew_role",   bestCrew.getRole(),
                "hours_remaining", round(newRemaining),
                "reason",          "Current crew at " + crewDuty
                        + "h duty. Swapped to "
                        + bestCrew.getCrewId()
                        + " with " + round(newRemaining)
                        + "h remaining",
                "score",           bestScore
        );
    }


    // ── PASSENGER RESOLUTION ALGORITHM ───────────────────────────────────────
    private Map<String, Object> resolvePassengers(
            Flight flight, Disruption disruption) {

        int delayMins = disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes() : 0;
        int paxCount  = disruption.getPassengersAffected() != null
                ? disruption.getPassengersAffected() : 0;

        if (delayMins <= 0 || paxCount <= 0) {
            return Map.of(
                    "action",        "NONE",
                    "affected_count", 0,
                    "reason",        "No passenger impact"
            );
        }

        if (delayMins > 120) {
            return Map.of(
                    "action",          "REBOOK",
                    "affected_count",  paxCount,
                    "priority",        "BUSINESS_FIRST",
                    "compensation",    "MEALS + HOTEL",
                    "reason",          "Delay > 2 hours. "
                            + "Full rebooking with compensation.",
                    "estimated_cost",  paxCount * 50.0
            );
        } else if (delayMins > 60) {
            return Map.of(
                    "action",         "REBOOK",
                    "affected_count", (int)(paxCount * 0.2),
                    "priority",       "BUSINESS_FIRST",
                    "compensation",   "MEALS",
                    "reason",         "Delay > 1 hour. "
                            + "Rebook connecting passengers.",
                    "estimated_cost", (int)(paxCount * 0.2) * 30.0
            );
        } else {
            return Map.of(
                    "action",         "NOTIFY",
                    "affected_count", paxCount,
                    "channel",        "SMS + APP",
                    "reason",         "Delay < 1 hour. "
                            + "Notify all passengers.",
                    "estimated_cost", paxCount * 2.0
            );
        }
    }


    // ── AIRCRAFT RESOLUTION ALGORITHM ────────────────────────────────────────
    private Map<String, Object> resolveAircraft(
            Flight flight,
            Disruption.DisruptionType type) {

        boolean needsSwap =
                type == Disruption.DisruptionType.AIRCRAFT_FAULT;

        if (!needsSwap) {
            return Map.of(
                    "action", "KEEP",
                    "reason", "Aircraft serviceable. Delay is operational."
            );
        }

        return Map.of(
                "action", "SWAP_REQUIRED",
                "reason", "Aircraft fault detected. "
                        + "Engineering inspection required. "
                        + "Request AOG team.",
                "priority", "URGENT"
        );
    }


    // ── DELAY ESTIMATION ALGORITHM ────────────────────────────────────────────
    private int estimateRemainingDelay(
            Flight flight, Disruption disruption,
            Map<String, Object> gateRes,
            Map<String, Object> crewRes) {

        int baseDelay = disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes() : 60;

        int reduction = 0;

        // Gate reassignment saves turnaround time
        if ("REASSIGNED".equals(gateRes.get("action"))) {
            reduction += 15;
        }

        // Crew swap takes time but prevents longer delay
        if ("SWAPPED".equals(crewRes.get("action"))) {
            reduction -= 20; // crew handover adds 20 mins
        }

        // Weather delays cannot be reduced algorithmically
        if (disruption.getType()
                == Disruption.DisruptionType.WEATHER_DELAY) {
            reduction = Math.min(reduction, 10);
        }

        return Math.max(15, baseDelay - reduction);
    }


    // ── DB APPLY METHODS ──────────────────────────────────────────────────────
    private void applyGateChange(Flight flight, String newGate) {
        try {
            // Free old gate
            if (flight.getAssignedGate() != null) {
                gateRepository.findAll().stream()
                        .filter(g -> g.getGateNumber()
                                .equals(flight.getAssignedGate()))
                        .findFirst().ifPresent(g -> {
                            g.setAvailable(true);
                            g.setCurrentFlightId(null);
                            gateRepository.save(g);
                        });
            }
            // Assign new gate
            gateRepository.findAll().stream()
                    .filter(g -> g.getGateNumber().equals(newGate))
                    .findFirst().ifPresent(g -> {
                        g.setAvailable(false);
                        g.setCurrentFlightId(flight.getFlightId());
                        gateRepository.save(g);
                    });
            // Update flight
            String oldGate = flight.getAssignedGate();
            flight.setAssignedGate(newGate);
            flight.setUpdatedAt(LocalDateTime.now());
            flightRepository.save(flight);
            log.info("Gate: {} → {}", oldGate, newGate);
        } catch (Exception e) {
            log.warn("Gate apply failed: {}", e.getMessage());
        }
    }

    private void applyCrewChange(Flight flight, String newCrewId) {
        try {
            // Free old crew
            if (flight.getAssignedCrewId() != null) {
                crewRepository.findAll().stream()
                        .filter(c -> c.getCrewId()
                                .equals(flight.getAssignedCrewId()))
                        .findFirst().ifPresent(c -> {
                            c.setAvailable(true);
                            c.setCurrentFlightId(null);
                            crewRepository.save(c);
                        });
            }
            // Assign new crew
            crewRepository.findAll().stream()
                    .filter(c -> c.getCrewId().equals(newCrewId))
                    .findFirst().ifPresent(c -> {
                        c.setAvailable(false);
                        c.setCurrentFlightId(flight.getFlightId());
                        crewRepository.save(c);
                    });
            // Update flight
            String oldCrew = flight.getAssignedCrewId();
            flight.setAssignedCrewId(newCrewId);
            flight.setUpdatedAt(LocalDateTime.now());
            flightRepository.save(flight);
            log.info("Crew: {} → {}", oldCrew, newCrewId);
        } catch (Exception e) {
            log.warn("Crew apply failed: {}", e.getMessage());
        }
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}