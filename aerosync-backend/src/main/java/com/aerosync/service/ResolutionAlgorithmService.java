
package com.aerosync.service;

import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import com.aerosync.synthetic.ReferenceAirports;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionAlgorithmService {

    private final FlightRepository flightRepository;
    private final GateRepository gateRepository;
    private final CrewRepository crewRepository;

    // ============================================================
    // MAIN RESOLUTION ALGORITHM
    // ============================================================

    public Map<String, Object> resolveAlgorithmically(
            Disruption disruption, Flight flight) {

        log.info("Algorithm resolving disruption {} for flight {}",
                disruption.getId(), flight.getFlightId());

        Map<String, Object> resolution = new LinkedHashMap<>();
        List<String> actionsLog = new ArrayList<>();

        Map<String, Object> ruleCheck = runRuleEngine(flight);
        resolution.put("rule_violations", ruleCheck.get("violations"));
        resolution.put("rule_status", ruleCheck.get("status"));

        Map<String, Object> gateRes =
                resolveGate(flight, disruption.getType());

        resolution.put("gate_resolution", gateRes);

        if ("REASSIGNED".equals(gateRes.get("action"))) {
            actionsLog.add("Gate reassigned: "
                    + flight.getAssignedGate()
                    + " -> " + gateRes.get("new_gate"));

            applyGateChange(flight, (String) gateRes.get("new_gate"));
        }

        Map<String, Object> crewRes =
                resolveCrew(flight, disruption.getType());

        resolution.put("crew_resolution", crewRes);

        if ("SWAPPED".equals(crewRes.get("action"))) {
            actionsLog.add("Crew swapped: "
                    + flight.getAssignedCrewId()
                    + " -> " + crewRes.get("new_crew_id"));

            applyCrewChange(
                    flight,
                    (String) crewRes.get("new_crew_id"));
        }

        Map<String, Object> paxRes =
                resolvePassengers(flight, disruption);

        resolution.put("passenger_resolution", paxRes);

        if (!"NONE".equals(paxRes.get("action"))) {
            actionsLog.add("Passengers: "
                    + paxRes.get("action")
                    + " - " + paxRes.get("affected_count") + " pax");
        }

        Map<String, Object> acRes =
                resolveAircraft(flight, disruption.getType());

        resolution.put("aircraft_resolution", acRes);

        if ("SWAP_REQUIRED".equals(acRes.get("action"))) {
            actionsLog.add("Aircraft replacement required");
        }

        int estimatedDelay = estimateRemainingDelay(
                disruption, gateRes, crewRes);

        double costImpact = estimatedDelay * 150.0
                + (disruption.getPassengersAffected() != null
                ? disruption.getPassengersAffected() * 30.0 : 0);

        resolution.put("estimated_new_delay_mins", estimatedDelay);
        resolution.put("total_cost_impact", costImpact);
        resolution.put("actions_taken", actionsLog);

        resolution.put("severity",
                estimatedDelay > 120 ? "CRITICAL"
                        : estimatedDelay > 60 ? "HIGH"
                        : estimatedDelay > 30 ? "MEDIUM" : "LOW");

        resolution.put("confidence_score", 0.95);
        resolution.put("resolution_method", "ALGORITHMIC");
        resolution.put("algorithm_version", "2.0");

        log.info("Algorithm resolved {} actions for flight {}",
                actionsLog.size(), flight.getFlightId());

        return resolution;
    }

    // ============================================================
    // RULE ENGINE
    // ============================================================

    private Map<String, Object> runRuleEngine(Flight flight) {

        List<Map<String, String>> violations = new ArrayList<>();
        String status = "CLEAR";

        // DGCA Rule 1: Crew duty hours
        if (flight.getCrewDutyHours() != null) {

            if (flight.getCrewDutyHours() >= 12.0) {
                violations.add(Map.of(
                        "rule", "DGCA_MAX_DUTY",
                        "severity", "BLOCK",
                        "message", "Crew at "
                                + flight.getCrewDutyHours()
                                + "h - DGCA 12h maximum exceeded"
                ));

                status = "BLOCKED";

            } else if (flight.getCrewDutyHours() >= 10.0) {

                violations.add(Map.of(
                        "rule", "DGCA_DUTY_WARNING",
                        "severity", "WARN",
                        "message", "Crew at "
                                + flight.getCrewDutyHours()
                                + "h - approaching DGCA limit"
                ));

                if (!"BLOCKED".equals(status)) {
                    status = "WARNING";
                }
            }
        }

        // DGCA Rule 2: Severe weather for ATR72
        if (flight.getWeatherScore() != null
                && flight.getWeatherScore() == 3
                && "ATR72".equals(flight.getAircraftType())) {

            violations.add(Map.of(
                    "rule", "WEATHER_ATR72_BLOCK",
                    "severity", "BLOCK",
                    "message", "Severe weather - ATR72 operations suspended"
            ));

            status = "BLOCKED";
        }

        // Rule 3: Gate turnaround
        if (flight.getGateTurnaroundMins() != null
                && flight.getGateTurnaroundMins() < 20) {

            violations.add(Map.of(
                    "rule", "GATE_TURNAROUND_CRITICAL",
                    "severity", "WARN",
                    "message", "Turnaround "
                            + flight.getGateTurnaroundMins()
                            + " min - critically short"
            ));

            if (!"BLOCKED".equals(status)) {
                status = "WARNING";
            }
        }

        return Map.of(
                "status", status,
                "violations", violations,
                "can_depart", !"BLOCKED".equals(status)
        );
    }

    // ============================================================
    // GATE RESOLUTION
    // ============================================================

    private Map<String, Object> resolveGate(
            Flight flight,
            Disruption.DisruptionType type) {

        boolean needsReassignment =
                type == Disruption.DisruptionType.GATE_CONFLICT
                        || (flight.getGateTurnaroundMins() != null
                        && flight.getGateTurnaroundMins() < 30);

        if (!needsReassignment) {

            Map<String, Object> result = new LinkedHashMap<>();

            result.put("action", "KEEP");

            if (flight.getAssignedGate() != null) {
                result.put("new_gate", flight.getAssignedGate());
            }

            result.put("reason", "Gate adequate for current situation");
            result.put("score", 100);

            return result;
        }

        List<Gate> available = gateRepository.findByAvailableTrue();

        if (available == null) {
            available = Collections.emptyList();
        }

        String aircraftType = flight.getAircraftType() != null
                ? flight.getAircraftType()
                : "A320";

        Gate bestGate = null;
        int bestScore = -1;

        for (Gate gate : available) {

            if (gate == null || gate.getGateNumber() == null) {
                continue;
            }

            // Never propose a gate of another airport (Stage 3 airport scope)
            if (!gateUsableFor(gate, flight)) {
                continue;
            }

            // Exact aircraft compatibility
            if (!hasExactCsvValue(
                    gate.getCompatibleTypes(), aircraftType)) {
                continue;
            }

            // Do not select the currently assigned gate
            if (Objects.equals(
                    gate.getGateNumber(),
                    flight.getAssignedGate())) {
                continue;
            }

            int score = 50;

            // Prefer same terminal
            String assignedGate = flight.getAssignedGate();

            if (assignedGate != null && !assignedGate.isBlank()
                    && gate.getTerminal() != null) {

                String currentTerminal =
                        assignedGate.substring(0, 1);

                if (gate.getTerminal().contains(currentTerminal)) {
                    score += 30;
                }
            }

            // Prefer gates compatible with more aircraft types
            score += gate.getCompatibleTypes()
                    .split(",").length * 5;

            if (score > bestScore) {
                bestScore = score;
                bestGate = gate;
            }
        }

        if (bestGate == null) {

            Map<String, Object> result = new LinkedHashMap<>();

            result.put("action", "KEEP");

            if (flight.getAssignedGate() != null) {
                result.put("new_gate", flight.getAssignedGate());
            }

            result.put(
                    "reason",
                    "No compatible gate available - keeping current");

            result.put("score", 0);

            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("action", "REASSIGNED");
        result.put("new_gate", bestGate.getGateNumber());

        result.put(
                "terminal",
                bestGate.getTerminal() != null
                        ? bestGate.getTerminal() : "T1");

        result.put(
                "reason",
                "Gate conflict/tight turnaround resolved. "
                        + "Reassigned to compatible gate "
                        + bestGate.getGateNumber());

        result.put("score", bestScore);

        return result;
    }

    // ============================================================
    // CREW RESOLUTION
    // ============================================================

    private Map<String, Object> resolveCrew(
            Flight flight,
            Disruption.DisruptionType type) {

        double crewDuty = flight.getCrewDutyHours() != null
                ? flight.getCrewDutyHours()
                : 0.0;

        boolean needsSwap =
                crewDuty >= 10.0
                        || type == Disruption.DisruptionType.CREW_TIMEOUT;

        if (!needsSwap) {

            Map<String, Object> result = new LinkedHashMap<>();

            result.put("action", "KEEP");

            if (flight.getAssignedCrewId() != null) {
                result.put(
                        "new_crew_id",
                        flight.getAssignedCrewId());
            }

            result.put(
                    "reason",
                    "Crew duty hours acceptable: " + crewDuty + "/12h");

            result.put("hours_remaining", 12.0 - crewDuty);

            return result;
        }

        String aircraftType = flight.getAircraftType() != null
                ? flight.getAircraftType()
                : "A320";

        List<Crew> available = crewRepository.findByAvailableTrue();

        if (available == null) {
            available = Collections.emptyList();
        }

        Crew bestCrew = null;
        double bestScore = -1;

        for (Crew crew : available) {

            if (crew == null || crew.getCrewId() == null) {
                continue;
            }

            // Do not select current crew
            if (Objects.equals(
                    crew.getCrewId(),
                    flight.getAssignedCrewId())) {
                continue;
            }

            // Require exact aircraft certification
            if (!hasExactCsvValue(
                    crew.getCertifications(), aircraftType)) {
                continue;
            }

            double dutyUsed = crew.getDutyHoursUsed() != null
                    ? crew.getDutyHoursUsed()
                    : 0.0;

            double maxDuty = crew.getMaxDutyHours() != null
                    ? crew.getMaxDutyHours()
                    : 12.0;

            double remaining = maxDuty - dutyUsed;

            // At least two hours remaining
            if (remaining < 2.0) {
                continue;
            }

            double score = remaining * 10;

            if ("PILOT".equalsIgnoreCase(crew.getRole())) {
                score += 20;
            }

            if (score > bestScore) {
                bestScore = score;
                bestCrew = crew;
            }
        }

        if (bestCrew == null) {

            Map<String, Object> result = new LinkedHashMap<>();

            result.put("action", "KEEP");

            if (flight.getAssignedCrewId() != null) {
                result.put(
                        "new_crew_id",
                        flight.getAssignedCrewId());
            }

            result.put(
                    "reason",
                    "No qualified crew available - "
                            + "manual escalation required");

            result.put("hours_remaining", 12.0 - crewDuty);

            return result;
        }

        double dutyUsed = bestCrew.getDutyHoursUsed() != null
                ? bestCrew.getDutyHoursUsed()
                : 0.0;

        double maxDuty = bestCrew.getMaxDutyHours() != null
                ? bestCrew.getMaxDutyHours()
                : 12.0;

        double newRemaining = maxDuty - dutyUsed;

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("action", "SWAPPED");

        result.put(
                "old_crew_id",
                flight.getAssignedCrewId() != null
                        ? flight.getAssignedCrewId()
                        : "NONE");

        result.put("new_crew_id", bestCrew.getCrewId());

        if (bestCrew.getRole() != null) {
            result.put("new_crew_role", bestCrew.getRole());
        }

        result.put("hours_remaining", round(newRemaining));

        result.put(
                "reason",
                "Current crew at " + crewDuty
                        + "h duty. Swapped to "
                        + bestCrew.getCrewId()
                        + " with " + round(newRemaining)
                        + "h remaining");

        result.put("score", bestScore);

        return result;
    }

    // ============================================================
    // PASSENGER RESOLUTION
    // ============================================================

    private Map<String, Object> resolvePassengers(
            Flight flight,
            Disruption disruption) {

        int delayMins = disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes()
                : 0;

        int paxCount = disruption.getPassengersAffected() != null
                ? disruption.getPassengersAffected()
                : 0;

        if (delayMins <= 0 || paxCount <= 0) {
            return Map.of(
                    "action", "NONE",
                    "affected_count", 0,
                    "reason", "No passenger impact"
            );
        }

        if (delayMins > 120) {
            return Map.of(
                    "action", "REBOOK",
                    "affected_count", paxCount,
                    "priority", "BUSINESS_FIRST",
                    "compensation", "MEALS + HOTEL",
                    "reason",
                    "Delay > 2 hours. Full rebooking with compensation.",
                    "estimated_cost", paxCount * 50.0
            );

        } else if (delayMins > 60) {

            int affected = (int) (paxCount * 0.2);

            return Map.of(
                    "action", "REBOOK",
                    "affected_count", affected,
                    "priority", "BUSINESS_FIRST",
                    "compensation", "MEALS",
                    "reason",
                    "Delay > 1 hour. Rebook connecting passengers.",
                    "estimated_cost", affected * 30.0
            );
        }

        return Map.of(
                "action", "NOTIFY",
                "affected_count", paxCount,
                "channel", "SMS + APP",
                "reason", "Delay < 1 hour. Notify all passengers.",
                "estimated_cost", paxCount * 2.0
        );
    }

    // ============================================================
    // AIRCRAFT RESOLUTION
    // ============================================================

    private Map<String, Object> resolveAircraft(
            Flight flight,
            Disruption.DisruptionType type) {

        if (type != Disruption.DisruptionType.AIRCRAFT_FAULT) {
            return Map.of(
                    "action", "KEEP",
                    "reason",
                    "Aircraft serviceable. Delay is operational."
            );
        }

        return Map.of(
                "action", "SWAP_REQUIRED",
                "reason",
                "Aircraft fault detected. Engineering inspection "
                        + "required. Request AOG team.",
                "priority", "URGENT"
        );
    }

    // ============================================================
    // DELAY ESTIMATION
    // ============================================================

    private int estimateRemainingDelay(
            Disruption disruption,
            Map<String, Object> gateRes,
            Map<String, Object> crewRes) {

        int baseDelay = disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes()
                : 60;

        int reduction = 0;

        if ("REASSIGNED".equals(gateRes.get("action"))) {
            reduction += 15;
        }

        if ("SWAPPED".equals(crewRes.get("action"))) {
            reduction -= 20;
        }

        if (disruption.getType()
                == Disruption.DisruptionType.WEATHER_DELAY) {
            reduction = Math.min(reduction, 10);
        }

        return Math.max(15, baseDelay - reduction);
    }

    // ============================================================
    // DATABASE APPLICATION METHODS
    // ============================================================

    private void applyGateChange(Flight flight, String newGate) {

        if (newGate == null || newGate.isBlank()
                || "null".equalsIgnoreCase(newGate.trim())) {
            return;
        }

        try {
            List<Gate> gates = gateRepository.findAll();

            // Validate the replacement BEFORE releasing the current gate.
            Gate targetGate = gates.stream()
                    .filter(g -> g != null
                            && Objects.equals(g.getGateNumber(), newGate)
                            && gateUsableFor(g, flight))
                    .findFirst()
                    .orElse(null);

            if (targetGate == null) {
                log.warn("Gate apply skipped: target gate {} not found", newGate);
                return;
            }

            if (Objects.equals(flight.getAssignedGate(), newGate)) {
                return;
            }

            if (flight.getAssignedGate() != null) {
                gates.stream()
                        .filter(g -> g != null
                                && Objects.equals(g.getGateNumber(),
                                        flight.getAssignedGate())
                                && gateUsableFor(g, flight))
                        .findFirst()
                        .ifPresent(g -> {
                            g.setAvailable(true);
                            g.setCurrentFlightId(null);
                            gateRepository.save(g);
                        });
            }

            targetGate.setAvailable(false);
            targetGate.setCurrentFlightId(flight.getFlightId());
            gateRepository.save(targetGate);

            String oldGate = flight.getAssignedGate();
            flight.setAssignedGate(newGate);
            flight.setUpdatedAt(LocalDateTime.now());
            flightRepository.save(flight);

            log.info("Gate changed: {} -> {}", oldGate, newGate);

        } catch (Exception e) {
            log.warn("Gate apply failed: {}", e.getMessage());
        }
    }

    private void applyCrewChange(Flight flight, String newCrewId) {

        if (newCrewId == null || newCrewId.isBlank()
                || "null".equalsIgnoreCase(newCrewId.trim())) {
            return;
        }

        try {
            List<Crew> crewMembers = crewRepository.findAll();

            // Validate the replacement BEFORE releasing the current crew.
            Crew targetCrew = crewMembers.stream()
                    .filter(c -> c != null
                            && Objects.equals(c.getCrewId(), newCrewId))
                    .findFirst()
                    .orElse(null);

            if (targetCrew == null) {
                log.warn("Crew apply skipped: target crew {} not found", newCrewId);
                return;
            }

            if (Objects.equals(flight.getAssignedCrewId(), newCrewId)) {
                return;
            }

            if (flight.getAssignedCrewId() != null) {
                crewMembers.stream()
                        .filter(c -> c != null
                                && Objects.equals(c.getCrewId(),
                                        flight.getAssignedCrewId()))
                        .findFirst()
                        .ifPresent(c -> {
                            c.setAvailable(true);
                            c.setCurrentFlightId(null);
                            crewRepository.save(c);
                        });
            }

            targetCrew.setAvailable(false);
            targetCrew.setCurrentFlightId(flight.getFlightId());
            crewRepository.save(targetCrew);

            String oldCrew = flight.getAssignedCrewId();
            flight.setAssignedCrewId(newCrewId);
            flight.setUpdatedAt(LocalDateTime.now());
            flightRepository.save(flight);

            log.info("Crew changed: {} -> {}", oldCrew, newCrewId);

        } catch (Exception e) {
            log.warn("Crew apply failed: {}", e.getMessage());
        }
    }


    // ============================================================
    // UTILITY METHODS
    // ============================================================

    /**
     * Stage 3 airport scope: a gate can serve a flight when it belongs to the
     * flight's departure airport, or has no airport (the gates created before
     * the operational network, a shared pool). Gates of other airports are
     * never proposed, assigned or released for this flight.
     */
    static boolean gateUsableFor(Gate gate, Flight flight) {
        if (gate == null) {
            return false;
        }
        if (gate.getAirportId() == null) {
            return true;
        }
        String origin = flight.getOrigin();
        if (origin == null || !origin.trim().matches("[A-Za-z]{3}")) {
            return false;
        }
        return gate.getAirportId().equals(
                ReferenceAirports.airportUuid(origin.trim().toUpperCase(Locale.ROOT)));
    }

    private boolean hasExactCsvValue(
            String csvValues,
            String expectedValue) {

        if (csvValues == null || expectedValue == null) {
            return false;
        }

        return Arrays.stream(csvValues.split(","))
                .map(String::trim)
                .anyMatch(value ->
                        value.equalsIgnoreCase(expectedValue));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // ============================================================
    // PROPOSE ONLY
    // Does NOT apply changes to the database
    // ============================================================

    public Map<String, Object> proposeOnly(
            Disruption disruption,
            Flight flight) {

        log.info("Proposing resolution for flight {} - not applying yet",
                flight.getFlightId());

        Map<String, Object> proposal = new LinkedHashMap<>();
        List<String> actionsLog = new ArrayList<>();

        Map<String, Object> ruleCheck = runRuleEngine(flight);

        proposal.put("rule_violations", ruleCheck.get("violations"));
        proposal.put("rule_status", ruleCheck.get("status"));

        Map<String, Object> gateRes =
                resolveGate(flight, disruption.getType());

        proposal.put("gate_resolution", gateRes);

        if ("REASSIGNED".equals(gateRes.get("action"))) {
            actionsLog.add("Gate reassign: "
                    + flight.getAssignedGate()
                    + " -> " + gateRes.get("new_gate"));
        }

        Map<String, Object> crewRes =
                resolveCrew(flight, disruption.getType());

        proposal.put("crew_resolution", crewRes);

        if ("SWAPPED".equals(crewRes.get("action"))) {
            actionsLog.add("Crew swap: "
                    + flight.getAssignedCrewId()
                    + " -> " + crewRes.get("new_crew_id"));
        }

        proposal.put(
                "passenger_resolution",
                resolvePassengers(flight, disruption));

        proposal.put(
                "aircraft_resolution",
                resolveAircraft(flight, disruption.getType()));

        int estimatedDelay = estimateRemainingDelay(
                disruption, gateRes, crewRes);

        double costImpact = estimatedDelay * 150.0
                + (disruption.getPassengersAffected() != null
                ? disruption.getPassengersAffected() * 30.0
                : 0);

        proposal.put("estimated_new_delay_mins", estimatedDelay);
        proposal.put("total_cost_impact", costImpact);
        proposal.put("actions_proposed", actionsLog);

        proposal.put(
                "severity",
                estimatedDelay > 120 ? "CRITICAL"
                        : estimatedDelay > 60 ? "HIGH"
                        : estimatedDelay > 30 ? "MEDIUM"
                        : "LOW");

        proposal.put("confidence_score", 0.95);
        proposal.put("resolution_method", "ALGORITHMIC_PROPOSAL");
        proposal.put("pending_approval", true);

        log.info("Proposal ready - {} actions proposed",
                actionsLog.size());

        return proposal;
    }

    // ============================================================
    // APPLY APPROVED RESOLUTION
    // ============================================================

    @SuppressWarnings("unchecked")
    public void applyResolution(
            Map<String, Object> approvedResolution,
            Flight flight) {

        log.info("Applying approved resolution for flight {}",
                flight.getFlightId());

        Map<String, Object> gateRes =
                (Map<String, Object>) approvedResolution.get(
                        "gate_resolution");

        if (gateRes != null
                && "REASSIGNED".equals(gateRes.get("action"))) {

            String newGate = (String) gateRes.get("new_gate");

            if (newGate != null && !newGate.isBlank()) {
                applyGateChange(flight, newGate);
            }
        }

        Map<String, Object> crewRes =
                (Map<String, Object>) approvedResolution.get(
                        "crew_resolution");

        if (crewRes != null
                && "SWAPPED".equals(crewRes.get("action"))) {

            String newCrew = (String) crewRes.get("new_crew_id");

            if (newCrew != null && !newCrew.isBlank()) {
                applyCrewChange(flight, newCrew);
            }
        }

        log.info("Approved resolution applied for flight {}",
                flight.getFlightId());
    }

    // ============================================================
    // RESOLVE WITH CONSTRAINT
    // ============================================================

    public Map<String, Object> resolveWithConstraint(
            Disruption disruption,
            Flight flight,
            String rejectionReason) {

        log.info("Re-resolving with constraint: {}", rejectionReason);

        String excludeCrewId = null;
        String excludeGate = null;

        if (rejectionReason != null) {

            Matcher crewMatcher = Pattern
                    .compile("CR-\\d+")
                    .matcher(rejectionReason);

            if (crewMatcher.find()) {
                excludeCrewId = crewMatcher.group();
            }

            Matcher gateMatcher = Pattern
                    .compile("[A-C]\\d")
                    .matcher(rejectionReason);

            if (gateMatcher.find()) {
                excludeGate = gateMatcher.group();
            }
        }

        // Resources temporarily marked unavailable, with their previous
        // availability so exactly that state is restored afterwards.
        Crew excludedCrew = null;
        Boolean excludedCrewWasAvailable = null;
        Gate excludedGateRecord = null;
        Boolean excludedGateWasAvailable = null;

        try {
            if (excludeCrewId != null) {

                final String id = excludeCrewId;

                excludedCrew = crewRepository.findAll().stream()
                        .filter(c -> c != null
                                && Objects.equals(c.getCrewId(), id))
                        .findFirst()
                        .orElse(null);

                if (excludedCrew != null) {
                    excludedCrewWasAvailable = excludedCrew.getAvailable();
                    excludedCrew.setAvailable(false);
                    crewRepository.save(excludedCrew);
                }
            }

            if (excludeGate != null) {

                final String gateNumber = excludeGate;

                // Only gates this flight could use (its airport or the shared
                // pool): free text must not toggle another airport's gate.
                excludedGateRecord = gateRepository.findAll().stream()
                        .filter(g -> g != null
                                && Objects.equals(
                                g.getGateNumber(), gateNumber)
                                && gateUsableFor(g, flight))
                        .findFirst()
                        .orElse(null);

                if (excludedGateRecord != null) {
                    excludedGateWasAvailable = excludedGateRecord.getAvailable();
                    excludedGateRecord.setAvailable(false);
                    gateRepository.save(excludedGateRecord);
                }
            }

            Map<String, Object> revised =
                    proposeOnly(disruption, flight);

            revised.put("rejection_reason", rejectionReason);
            revised.put("excluded_crew", excludeCrewId);
            revised.put("excluded_gate", excludeGate);
            revised.put("iteration", 2);

            return revised;

        } finally {

            // Restore the temporarily excluded crew to its previous state
            if (excludedCrew != null) {
                excludedCrew.setAvailable(excludedCrewWasAvailable);
                crewRepository.save(excludedCrew);
            }

            // Restore the temporarily excluded gate to its previous state
            if (excludedGateRecord != null) {
                excludedGateRecord.setAvailable(excludedGateWasAvailable);
                gateRepository.save(excludedGateRecord);
            }
        }
    }
}
