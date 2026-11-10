package com.aerosync.service;

import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResolutionAlgorithmService}: gate and crew selection,
 * null assignments, propose-only versus apply.
 *
 * <p>Plain Mockito (no Spring context). These tests cover current expected
 * behavior, including safe handling of missing gate and crew assignments.</p>
 */
@ExtendWith(MockitoExtension.class)
class ResolutionAlgorithmServiceTest {

    @Mock private FlightRepository flightRepository;
    @Mock private GateRepository gateRepository;
    @Mock private CrewRepository crewRepository;

    private ResolutionAlgorithmService service;

    @BeforeEach
    void setUp() {
        service = new ResolutionAlgorithmService(flightRepository, gateRepository, crewRepository);
    }

    // ?? Fixtures (shaped like the seeded data: gates A1/A2 in T1, B1?B3 in T2, C1?C2 in T3) ??

    private static Flight flight(String gate, String crewId) {
        Flight flight = new Flight();
        flight.setFlightId("AI-101");
        flight.setOrigin("BOM");
        flight.setDestination("DEL");
        flight.setAircraftType("A320");
        flight.setCrewDutyHours(6.0);
        flight.setGateTurnaroundMins(45);
        flight.setAssignedGate(gate);
        flight.setAssignedCrewId(crewId);
        return flight;
    }

    private static Disruption disruption(DisruptionType type) {
        Disruption disruption = new Disruption();
        disruption.setId(7L);
        disruption.setFlightId("AI-101");
        disruption.setType(type);
        disruption.setDelayMinutes(45);
        disruption.setPassengersAffected(120);
        return disruption;
    }

    private static Gate gate(String number, String terminal, String compatibleTypes, boolean available) {
        Gate gate = new Gate();
        gate.setGateNumber(number);
        gate.setTerminal(terminal);
        gate.setCompatibleTypes(compatibleTypes);
        gate.setAvailable(available);
        return gate;
    }

    private static Crew crew(String crewId, String role, String certifications, double hoursUsed, boolean available) {
        Crew crew = new Crew();
        crew.setCrewId(crewId);
        crew.setRole(role);
        crew.setCertifications(certifications);
        crew.setDutyHoursUsed(hoursUsed);
        crew.setMaxDutyHours(12.0);
        crew.setAvailable(available);
        return crew;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> resolution, String key) {
        return (Map<String, Object>) resolution.get(key);
    }

    // ?? Gate selection ?????????????????????????????????????????????????????????

    @Test
    void gateConflict_withNoAvailableGates_keepsCurrentGate() {
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of());

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
        assertEquals("A1", gate.get("new_gate"));
        assertEquals(0, gate.get("score"));
        assertTrue(((String) gate.get("reason")).startsWith("No compatible gate available"));
    }

    @Test
    void gateConflict_picksTheHighestScoringCompatibleGate() {
        // B1: 50 + 1 type * 5 = 55; C1: 50 + 3 types * 5 = 65; B3 is not compatible with an A320.
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("B1", "T2", "A320", true),
                gate("C1", "T3", "A320,B737,A321", true),
                gate("B3", "T2", "ATR72", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("REASSIGNED", gate.get("action"));
        assertEquals("C1", gate.get("new_gate"));
        assertEquals("T3", gate.get("terminal"));
        assertEquals(65, gate.get("score"));
    }

    @Test
    void gateConflict_withOnlyIncompatibleGates_keepsCurrentGate() {
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("B3", "T2", "ATR72", true),
                gate("C2", "T3", null, true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
        assertEquals("A1", gate.get("new_gate"));
    }

    @Test
    void gateConflict_neverReassignsToTheCurrentGate() {
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(gate("A1", "T1", "A320,B737", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
    }

    @Test
    void gateSelection_usesTheFlightsAircraftType() {
        Flight atr = flight("A1", "CR-001");
        atr.setAircraftType("ATR72");
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("C1", "T3", "A320,B737,A321", true),
                gate("B3", "T2", "ATR72", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), atr), "gate_resolution");

        assertEquals("B3", gate.get("new_gate"));
    }

    @Test
    void tightTurnaround_triggersGateReassignmentWithoutAGateConflict() {
        Flight tight = flight("A1", "CR-001");
        tight.setGateTurnaroundMins(25);
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(gate("B1", "T2", "A320", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.WEATHER_DELAY), tight), "gate_resolution");

        assertEquals("REASSIGNED", gate.get("action"));
        assertEquals("B1", gate.get("new_gate"));
    }

    @Test
    void adequateGate_isKeptWithoutQueryingGates() {
        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.WEATHER_DELAY), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
        assertEquals(100, gate.get("score"));
        verify(gateRepository, never()).findByAvailableTrue();
    }

    @Test
    void knownDefect_sameTerminalBonusNeverMatchesTerminalNames() {
        // The +30 "same terminal" bonus checks whether the gate's terminal contains the
        // first letter of the current gate ("A" for A1). With terminal names like "T1"
        // that never matches, so A2 (same terminal T1 as A1) loses to B1 in T2 on the
        // compatibility count alone. Pinned here; fixing it should flip this expectation.
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("A2", "T1", "A320", true),
                gate("B1", "T2", "A320,B737", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight("A1", "CR-001")), "gate_resolution");

        assertEquals("B1", gate.get("new_gate"));
        assertEquals(60, gate.get("score"));
    }

    // ?? Crew selection ?????????????????????????????????????????????????????????

    @Test
    void crewTimeout_withNoAvailableCrew_keepsCrewAndEscalates() {
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of());

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("KEEP", crew.get("action"));
        assertEquals("CR-001", crew.get("new_crew_id"));
        assertTrue(((String) crew.get("reason")).contains("manual escalation required"));
    }

    @Test
    void crewTimeout_skipsCrewWithoutTheAircraftCertification() {
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(
                crew("CR-010", "PILOT", "B737", 0.0, true),
                crew("CR-011", "PILOT", null, 0.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("KEEP", crew.get("action"));
    }

    @Test
    void crewTimeout_skipsCrewWithLessThanTwoHoursLeft() {
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(
                crew("CR-010", "PILOT", "A320", 10.5, true),
                crew("CR-012", "CO_PILOT", "A320,A321", 4.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("SWAPPED", crew.get("action"));
        assertEquals("CR-012", crew.get("new_crew_id"));
        assertEquals(8.0, crew.get("hours_remaining"));
    }

    @Test
    void crewTimeout_neverSwapsInTheCurrentCrew() {
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(crew("CR-001", "PILOT", "A320", 0.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("KEEP", crew.get("action"));
    }

    @Test
    void crewRole_isAPreferenceNotAFilter() {
        // PILOT: 9 h left * 10 + 20 = 110 beats CABIN_CREW: 10 h left * 10 = 100.
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(
                crew("CR-020", "CABIN_CREW", "A320", 2.0, true),
                crew("CR-021", "PILOT", "A320", 3.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("CR-021", crew.get("new_crew_id"));
        assertEquals("PILOT", crew.get("new_crew_role"));
    }

    @Test
    void crewRoleMismatch_isNotRejected_whenItIsTheOnlyCertifiedCrew() {
        // The service does not check the replaced crew member's role: a certified
        // cabin crew member is proposed when no pilot qualifies.
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(
                crew("CR-030", "PILOT", "B737", 0.0, true),
                crew("CR-031", "CABIN_CREW", "A320", 1.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", "CR-001")), "crew_resolution");

        assertEquals("SWAPPED", crew.get("action"));
        assertEquals("CR-031", crew.get("new_crew_id"));
        assertEquals("CABIN_CREW", crew.get("new_crew_role"));
    }

    @Test
    void highDutyHours_triggerACrewSwapForAnyDisruption() {
        Flight tired = flight("A1", "CR-001");
        tired.setCrewDutyHours(10.5);
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(crew("CR-002", "PILOT", "A320", 1.0, true)));

        Map<String, Object> proposal = service.proposeOnly(disruption(DisruptionType.WEATHER_DELAY), tired);

        assertEquals("SWAPPED", section(proposal, "crew_resolution").get("action"));
        assertEquals("WARNING", proposal.get("rule_status"));
    }

    // ?? Null or missing assignments ????????????????????????????????????????????

    @Test
    void missingCrewAssignment_swapReportsNoneAsTheOldCrew() {
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(crew("CR-002", "PILOT", "A320", 1.0, true)));

        Map<String, Object> crew = section(service.proposeOnly(disruption(DisruptionType.CREW_TIMEOUT), flight("A1", null)), "crew_resolution");

        assertEquals("SWAPPED", crew.get("action"));
        assertEquals("NONE", crew.get("old_crew_id"));
        assertEquals("CR-002", crew.get("new_crew_id"));
    }

    @Test
    void missingGateAssignment_gateConflictStillFindsAGate() {
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(gate("B1", "T2", "A320,B737", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight(null, "CR-001")), "gate_resolution");

        assertEquals("REASSIGNED", gate.get("action"));
        assertEquals("B1", gate.get("new_gate"));
        assertEquals(60, gate.get("score"));
    }

    @Test
    void missingAircraftType_defaultsToA320() {
        Flight unknownType = flight("A1", "CR-001");
        unknownType.setAircraftType(null);
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("B3", "T2", "ATR72", true),
                gate("B1", "T2", "A320", true)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), unknownType), "gate_resolution");

        assertEquals("B1", gate.get("new_gate"));
    }

    @Test
    void keepingAMissingGate_isHandledWithoutNullPointerException() {
        Disruption weather = disruption(DisruptionType.WEATHER_DELAY);
        Flight noGate = flight(null, "CR-001");

        Map<String, Object> result = service.proposeOnly(weather, noGate);
        Map<String, Object> gate = section(result, "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
        assertFalse(gate.containsKey("new_gate"));
    }

    @Test
    void keepingAMissingCrew_isHandledWithoutNullPointerException() {
        Disruption weather = disruption(DisruptionType.WEATHER_DELAY);
        Flight noCrew = flight("A1", null);

        Map<String, Object> result = service.proposeOnly(weather, noCrew);
        Map<String, Object> crew = section(result, "crew_resolution");

        assertEquals("KEEP", crew.get("action"));
        assertFalse(crew.containsKey("new_crew_id"));
    }

    // ?? Propose only versus apply ??????????????????????????????????????????????

    @Test
    void proposeOnly_doesNotWriteToTheDatabase() {
        Gate current = gate("A1", "T1", "A320", false);
        Gate target = gate("C1", "T3", "A320,B737,A321", true);
        Crew newCrew = crew("CR-002", "PILOT", "A320", 1.0, true);
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(target));
        when(crewRepository.findByAvailableTrue()).thenReturn(List.of(newCrew));
        Flight flight = flight("A1", "CR-001");
        flight.setCrewDutyHours(11.0);

        Map<String, Object> proposal = service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT), flight);

        assertEquals("REASSIGNED", section(proposal, "gate_resolution").get("action"));
        assertEquals("SWAPPED", section(proposal, "crew_resolution").get("action"));
        assertEquals(Boolean.TRUE, proposal.get("pending_approval"));
        assertEquals("ALGORITHMIC_PROPOSAL", proposal.get("resolution_method"));
        assertEquals(2, ((List<?>) proposal.get("actions_proposed")).size());
        // Nothing changed or saved.
        assertEquals("A1", flight.getAssignedGate());
        assertEquals("CR-001", flight.getAssignedCrewId());
        assertNull(flight.getUpdatedAt());
        assertFalse(current.getAvailable());
        assertTrue(target.getAvailable());
        assertTrue(newCrew.getAvailable());
        verify(flightRepository, never()).save(any());
        verify(gateRepository, never()).save(any());
        verify(crewRepository, never()).save(any());
        verify(gateRepository, never()).findAll();
        verify(crewRepository, never()).findAll();
    }

    @Test
    void resolveAlgorithmically_appliesChangesImmediately_unlikeProposeOnly() {
        Gate current = gate("A1", "T1", "A320", false);
        current.setCurrentFlightId("AI-101");
        Gate target = gate("C1", "T3", "A320,B737,A321", true);
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(target));
        when(gateRepository.findAll()).thenReturn(List.of(current, target));
        Flight flight = flight("A1", "CR-001");

        Map<String, Object> resolution = service.resolveAlgorithmically(disruption(DisruptionType.GATE_CONFLICT), flight);

        assertEquals("ALGORITHMIC", resolution.get("resolution_method"));
        assertEquals("C1", flight.getAssignedGate());
        verify(flightRepository).save(flight);
        verify(gateRepository).save(current);
        verify(gateRepository).save(target);
    }

    @Test
    void applyResolution_appliesApprovedGateAndCrewChanges() {
        Gate oldGate = gate("A1", "T1", "A320", false);
        oldGate.setCurrentFlightId("AI-101");
        Gate newGate = gate("B2", "T2", "A320,B737", true);
        Crew oldCrew = crew("CR-001", "PILOT", "A320", 9.0, false);
        oldCrew.setCurrentFlightId("AI-101");
        Crew newCrew = crew("CR-002", "PILOT", "A320", 1.0, true);
        when(gateRepository.findAll()).thenReturn(List.of(oldGate, newGate));
        when(crewRepository.findAll()).thenReturn(List.of(oldCrew, newCrew));
        Flight flight = flight("A1", "CR-001");

        Map<String, Object> approved = Map.of(
                "gate_resolution", Map.of("action", "REASSIGNED", "new_gate", "B2"),
                "crew_resolution", Map.of("action", "SWAPPED", "new_crew_id", "CR-002"));
        service.applyResolution(approved, flight);

        assertEquals("B2", flight.getAssignedGate());
        assertEquals("CR-002", flight.getAssignedCrewId());
        assertNotNull(flight.getUpdatedAt());
        assertTrue(oldGate.getAvailable());
        assertNull(oldGate.getCurrentFlightId());
        assertFalse(newGate.getAvailable());
        assertEquals("AI-101", newGate.getCurrentFlightId());
        assertTrue(oldCrew.getAvailable());
        assertNull(oldCrew.getCurrentFlightId());
        assertFalse(newCrew.getAvailable());
        assertEquals("AI-101", newCrew.getCurrentFlightId());
        verify(gateRepository).save(oldGate);
        verify(gateRepository).save(newGate);
        verify(crewRepository).save(oldCrew);
        verify(crewRepository).save(newCrew);
        verify(flightRepository, times(2)).save(flight);
    }

    @Test
    void applyResolution_ignoresKeepActionsAndMissingTargets() {
        Map<String, Object> crewWithoutTarget = new HashMap<>();
        crewWithoutTarget.put("action", "SWAPPED");
        crewWithoutTarget.put("new_crew_id", null);
        Map<String, Object> approved = Map.of(
                "gate_resolution", Map.of("action", "KEEP", "new_gate", "A1"),
                "crew_resolution", crewWithoutTarget);
        Flight flight = flight("A1", "CR-001");

        service.applyResolution(approved, flight);
        service.applyResolution(Map.of(
                "gate_resolution", Map.of("action", "REASSIGNED", "new_gate", "null")), flight);
        service.applyResolution(Map.of(), flight);

        assertEquals("A1", flight.getAssignedGate());
        assertEquals("CR-001", flight.getAssignedCrewId());
        verifyNoInteractions(flightRepository, gateRepository, crewRepository);
    }

    @Test
    void applyResolution_withoutAPreviousGate_onlyAssignsTheNewGate() {
        Gate newGate = gate("B2", "T2", "A320", true);
        when(gateRepository.findAll()).thenReturn(List.of(newGate));
        Flight flight = flight(null, "CR-001");

        service.applyResolution(Map.of("gate_resolution", Map.of("action", "REASSIGNED", "new_gate", "B2")), flight);

        assertEquals("B2", flight.getAssignedGate());
        assertFalse(newGate.getAvailable());
        verify(gateRepository, times(1)).findAll();
        verify(gateRepository).save(newGate);
        verify(flightRepository).save(flight);
    }
}
