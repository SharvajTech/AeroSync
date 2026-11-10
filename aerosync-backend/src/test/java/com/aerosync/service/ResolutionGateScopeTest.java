package com.aerosync.service;

import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import com.aerosync.synthetic.ReferenceAirports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 3 airport scope inside {@link ResolutionAlgorithmService}: a flight
 * only ever gets, frees or excludes gates of its departure airport or of the
 * shared pool (gates without an airport).
 */
@ExtendWith(MockitoExtension.class)
class ResolutionGateScopeTest {

    private static final UUID BOM = ReferenceAirports.airportUuid("BOM");
    private static final UUID DEL = ReferenceAirports.airportUuid("DEL");

    @Mock private FlightRepository flightRepository;
    @Mock private GateRepository gateRepository;
    @Mock private CrewRepository crewRepository;

    private ResolutionAlgorithmService service;

    @BeforeEach
    void setUp() {
        service = new ResolutionAlgorithmService(flightRepository, gateRepository, crewRepository);
    }

    private static Flight bomFlight(String gate) {
        Flight flight = new Flight();
        flight.setFlightId("AI-900");
        flight.setOrigin("BOM");
        flight.setDestination("DEL");
        flight.setAircraftType("A320");
        flight.setCrewDutyHours(6.0);
        flight.setGateTurnaroundMins(45);
        flight.setAssignedGate(gate);
        flight.setAssignedCrewId("CR-001");
        return flight;
    }

    private static Gate gate(String number, UUID airportId, String types, boolean available, String currentFlight) {
        Gate gate = new Gate();
        gate.setGateNumber(number);
        gate.setTerminal("T1");
        gate.setCompatibleTypes(types);
        gate.setAvailable(available);
        gate.setAirportId(airportId);
        gate.setCurrentFlightId(currentFlight);
        return gate;
    }

    private static Disruption disruption(DisruptionType type) {
        Disruption disruption = new Disruption();
        disruption.setId(11L);
        disruption.setFlightId("AI-900");
        disruption.setType(type);
        disruption.setDelayMinutes(40);
        disruption.setPassengersAffected(90);
        return disruption;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> resolution, String key) {
        return (Map<String, Object>) resolution.get(key);
    }

    @Test
    void gateBelongsToTheDepartureAirportOrToThePool() {
        Flight flight = bomFlight("A1");
        assertTrue(ResolutionAlgorithmService.gateUsableFor(gate("P1", null, "A320", true, null), flight));
        assertTrue(ResolutionAlgorithmService.gateUsableFor(gate("B1", BOM, "A320", true, null), flight));
        assertFalse(ResolutionAlgorithmService.gateUsableFor(gate("D1", DEL, "A320", true, null), flight));
        assertFalse(ResolutionAlgorithmService.gateUsableFor(null, flight));

        Flight unknownOrigin = bomFlight("A1");
        unknownOrigin.setOrigin(null);
        assertFalse(ResolutionAlgorithmService.gateUsableFor(gate("B1", BOM, "A320", true, null), unknownOrigin));
        assertTrue(ResolutionAlgorithmService.gateUsableFor(gate("P1", null, "A320", true, null), unknownOrigin));
    }

    @Test
    void neverProposesAGateOfAnotherAirport() {
        // D1 at DEL scores higher (more compatible types) but must not be offered to a BOM departure.
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(
                gate("D1", DEL, "A320,B737,A321,ATR72", true, null),
                gate("B1", BOM, "A320", true, null)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT),
                bomFlight("A1")), "gate_resolution");

        assertEquals("REASSIGNED", gate.get("action"));
        assertEquals("B1", gate.get("new_gate"));
    }

    @Test
    void keepsTheGateWhenOnlyOtherAirportsHaveFreeGates() {
        when(gateRepository.findByAvailableTrue()).thenReturn(List.of(gate("D1", DEL, "A320", true, null)));

        Map<String, Object> gate = section(service.proposeOnly(disruption(DisruptionType.GATE_CONFLICT),
                bomFlight("A1")), "gate_resolution");

        assertEquals("KEEP", gate.get("action"));
    }

    @Test
    void approvedGateOfAnotherAirportIsNotApplied() {
        Gate delGate = gate("D1", DEL, "A320", true, null);
        when(gateRepository.findAll()).thenReturn(List.of(delGate));
        Flight flight = bomFlight("A1");

        service.applyResolution(Map.of("gate_resolution", Map.of("action", "REASSIGNED", "new_gate", "D1")), flight);

        assertEquals("A1", flight.getAssignedGate());
        assertTrue(delGate.getAvailable());
        assertNull(delGate.getCurrentFlightId());
        verify(gateRepository, never()).save(any());
        verify(flightRepository, never()).save(any());
    }

    @Test
    void sameGateNumberAtAnotherAirportIsNeitherFreedNorTaken() {
        Gate delA1 = gate("A1", DEL, "A320", false, "AI-777");
        Gate poolA1 = gate("A1", null, "A320", false, "AI-900");
        Gate delB2 = gate("B2", DEL, "A320", true, null);
        Gate bomB2 = gate("B2", BOM, "A320", true, null);
        when(gateRepository.findAll()).thenReturn(List.of(delA1, poolA1, delB2, bomB2));
        Flight flight = bomFlight("A1");

        service.applyResolution(Map.of("gate_resolution", Map.of("action", "REASSIGNED", "new_gate", "B2")), flight);

        assertEquals("B2", flight.getAssignedGate());
        assertFalse(bomB2.getAvailable());
        assertEquals("AI-900", bomB2.getCurrentFlightId());
        assertTrue(poolA1.getAvailable());
        // DEL's gates with the same numbers are untouched.
        assertFalse(delA1.getAvailable());
        assertEquals("AI-777", delA1.getCurrentFlightId());
        assertTrue(delB2.getAvailable());
        verify(gateRepository, never()).save(delA1);
        verify(gateRepository, never()).save(delB2);
    }

    @Test
    void rejectionTextCannotToggleAnotherAirportsGate() {
        Gate delC1 = gate("C1", DEL, "A320", false, "AI-777");
        when(gateRepository.findAll()).thenReturn(List.of(delC1));

        service.resolveWithConstraint(disruption(DisruptionType.WEATHER_DELAY), bomFlight("A1"),
                "Gate C1 is closed");

        assertFalse(delC1.getAvailable());
        verify(gateRepository, never()).save(any());
    }

    @Test
    void rejectionRestoresThePreviousAvailabilityInsteadOfFreeingResources() {
        Gate occupiedPoolGate = gate("C2", null, "A320", false, "AI-555");
        Crew busyCrew = new Crew();
        busyCrew.setCrewId("CR-005");
        busyCrew.setAvailable(false);
        when(gateRepository.findAll()).thenReturn(List.of(occupiedPoolGate));
        when(crewRepository.findAll()).thenReturn(List.of(busyCrew));

        Map<String, Object> revised = service.resolveWithConstraint(disruption(DisruptionType.WEATHER_DELAY),
                bomFlight("A1"), "CR-005 is on leave and gate C2 is closed");

        assertEquals("C2", revised.get("excluded_gate"));
        assertEquals("CR-005", revised.get("excluded_crew"));
        // Both were unavailable before and stay unavailable (previously they were set to available).
        assertFalse(occupiedPoolGate.getAvailable());
        assertFalse(busyCrew.getAvailable());
    }
}
