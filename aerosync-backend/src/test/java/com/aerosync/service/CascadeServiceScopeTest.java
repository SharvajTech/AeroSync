package com.aerosync.service;

import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import com.aerosync.security.AirportScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A cascade only disrupts and re-resolves flights of the user's airports. */
@ExtendWith(MockitoExtension.class)
class CascadeServiceScopeTest {

    @Mock private FlightRepository flightRepository;
    @Mock private DisruptionService disruptionService;
    @Mock private ResolutionAlgorithmService algorithmService;

    private CascadeService cascadeService;

    @BeforeEach
    void setUp() {
        cascadeService = new CascadeService(flightRepository, disruptionService, algorithmService);
    }

    private static Flight flight(String id, String origin, String destination) {
        Flight flight = new Flight();
        flight.setFlightId(id);
        flight.setOrigin(origin);
        flight.setDestination(destination);
        flight.setStatus(Flight.FlightStatus.SCHEDULED);
        return flight;
    }

    @Test
    void connectingFlightsOfAnotherAirportAreLeftAlone() {
        when(flightRepository.findByFlightId("AI-900")).thenReturn(Optional.of(flight("AI-900", "BOM", "DEL")));

        Map<String, Object> result = cascadeService.resolveCascade("AI-900", 120, AirportScope.of("BOM"));

        assertEquals(0, result.get("flights_cascaded"));
        assertTrue(((String) result.get("cascade_scope_note")).contains("DEL"));
        verify(disruptionService, never()).triggerDisruption(anyString(), any(), anyInt());
        verify(algorithmService, never()).resolveAlgorithmically(any(), any());
    }

    @Test
    void connectingFlightsOfTheUsersAirportsAreCascaded() {
        Flight connecting = flight("AI-901", "DEL", "BLR");
        Disruption cascade = new Disruption();
        when(flightRepository.findByFlightId("AI-900")).thenReturn(Optional.of(flight("AI-900", "BOM", "DEL")));
        when(flightRepository.findByOriginAndStatus("DEL", Flight.FlightStatus.SCHEDULED))
                .thenReturn(List.of(connecting));
        when(disruptionService.triggerDisruption("AI-901", Disruption.DisruptionType.CASCADING_DELAY, 84))
                .thenReturn(cascade);
        when(algorithmService.resolveAlgorithmically(cascade, connecting)).thenReturn(new LinkedHashMap<>());

        Map<String, Object> result = cascadeService.resolveCascade("AI-900", 120, AirportScope.of("BOM", "DEL"));

        assertEquals(1, result.get("flights_cascaded"));
        assertFalse(result.containsKey("cascade_scope_note"));
    }
}
