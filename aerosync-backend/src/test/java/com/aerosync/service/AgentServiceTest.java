package com.aerosync.service;

import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// ── AgentService — the "LLM failure" test from the Part 8 spec ─────────────
// (successful retrieval / missing documents / empty retrieval are covered
// directly on RagService in RagServiceTest — this one exercises the full
// proposeResolution() path with Gemini failing, to prove the requirement:
// "If LLM/RAG fails, the system must still return the deterministic
// resolution.")
//
// Plain Mockito unit test — no Spring context — so it only needs
// mockito-junit-jupiter + junit-jupiter, not the full test-slice machinery.
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private FlightRepository flightRepository;
    @Mock private GateRepository gateRepository;
    @Mock private CrewRepository crewRepository;
    @Mock private DisruptionRepository disruptionRepository;
    @Mock private DisruptionService disruptionService;
    @Mock private CascadeService cascadeService;

    private Disruption disruption;
    private Flight flight;

    @BeforeEach
    void setUp() {
        flight = new Flight();
        flight.setFlightId("AI-101");
        flight.setOrigin("BOM");
        flight.setDestination("DEL");
        flight.setCrewDutyHours(9.0);
        flight.setGateTurnaroundMins(40);
        flight.setAircraftType("A320");

        disruption = new Disruption();
        disruption.setId(1L);
        disruption.setFlightId("AI-101");
        disruption.setType(Disruption.DisruptionType.WEATHER_DELAY);
        disruption.setDelayMinutes(45);
        disruption.setPassengersAffected(120);
        disruption.setStatus(Disruption.DisruptionStatus.DETECTED);

        when(disruptionRepository.findById(1L)).thenReturn(Optional.of(disruption));
        when(flightRepository.findByFlightId("AI-101")).thenReturn(Optional.of(flight));
    }

    @Test
    void proposeResolution_stillReturnsDeterministicResolution_whenGeminiFails() {
        // Gemini call fails — simulates an outage / API error / timeout.
        WebClient failingGeminiClient = Mockito.mock(WebClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(failingGeminiClient.post())
        .thenThrow(new RuntimeException("Gemini unreachable"));

        // RAG also reports unavailable, to isolate the LLM-failure path from
        // any RAG behaviour (RagServiceTest already covers RAG on its own).
        WebClient unavailableRagClient = Mockito.mock(WebClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(unavailableRagClient.post().uri(any(String.class)).bodyValue(any())
                .retrieve().bodyToMono(String.class)
                .timeout(any(Duration.class)).block())
                .thenReturn("{\"available\":false,\"chunks\":[],\"count\":0}");
        RagService ragService = new RagService(unavailableRagClient);

        ResolutionAlgorithmService resolutionAlgorithmService =
                new ResolutionAlgorithmService(flightRepository, gateRepository, crewRepository);

        AgentService agentService = new AgentService(
                flightRepository, gateRepository, crewRepository,
                disruptionRepository, disruptionService,
                resolutionAlgorithmService, cascadeService, ragService,
                failingGeminiClient);
        setPrivateField(agentService, "geminiModel", "gemini-2.5-flash");
        setPrivateField(agentService, "geminiApiKey", "test-key");

        Map<String, Object> proposal = agentService.proposeResolution(1L);

        // Decision — always present, algorithm never depends on Gemini/RAG
        assertNotNull(proposal.get("gate_resolution"), "Decision must still be present");
        assertNotNull(proposal.get("crew_resolution"), "Decision must still be present");
        assertEquals("deterministic_algorithm", proposal.get("decision_source"));

        // Evidence — present as a key even though RAG was unavailable
        assertNotNull(proposal.get("policy_evidence"));
        assertEquals("rag_retrieval", proposal.get("evidence_source"));

        // Explanation — falls back to the algorithmic explanation, never null/blank
        assertNotNull(proposal.get("ai_explanation"));
        assertFalse(((String) proposal.get("ai_explanation")).isBlank());
        assertEquals("algorithmic_fallback", proposal.get("explanation_source"));
    }

    private void setPrivateField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
