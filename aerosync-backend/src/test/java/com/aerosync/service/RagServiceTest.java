package com.aerosync.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// ── RagService tests ────────────────────────────────────────────────────────
// Covers the 3 retrieval-shaped scenarios from the Part 8 spec directly
// (successful retrieval, missing documents/index, empty retrieval); the
// 4th ("LLM failure") is covered in AgentServiceTest, since that's a
// property of the explanation step, not of retrieval itself.
//
// WebClient's fluent chain (.post().uri().bodyValue().retrieve()...) is
// mocked with Mockito.RETURNS_DEEP_STUBS — the standard pattern for
// testing a class built around WebClient's builder-style API without
// needing a real HTTP server.
@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    // ── Successful retrieval ──────────────────────────────────────────────
    @Test
    void retrieve_returnsChunks_onSuccessfulResponse() {
        String json = "{\"available\":true,\"backend\":\"faiss\",\"count\":1,"
                + "\"chunks\":[{\"doc_name\":\"crew_operations_policy\","
                + "\"doc_title\":\"AeroSync Crew Operations Policy (Demo)\","
                + "\"section\":\"Crew Reassignment Rules\","
                + "\"chunk_id\":\"crew_operations_policy::Crew Reassignment Rules::0\","
                + "\"text\":\"Crew may be reassigned...\",\"score\":0.85}]}";
        RagService service = new RagService(mockWebClientReturning(json));

        List<Map<String, Object>> result = service.retrieve("crew duty limit", 3);

        assertEquals(1, result.size());
        assertEquals("crew_operations_policy", result.get(0).get("doc_name"));
        assertEquals("Crew Reassignment Rules", result.get(0).get("section"));
    }

    // ── Missing documents / index not built yet (service reports unavailable) ─
    @Test
    void retrieve_returnsEmptyList_whenIndexUnavailable() {
        String json = "{\"available\":false,\"backend\":null,\"chunks\":[],\"count\":0}";
        RagService service = new RagService(mockWebClientReturning(json));

        List<Map<String, Object>> result = service.retrieve("any query", 3);

        assertTrue(result.isEmpty());
    }

    // ── Empty retrieval — index exists, but nothing relevant was found ────────
    @Test
    void retrieve_returnsEmptyList_onEmptyChunkList() {
        String json = "{\"available\":true,\"backend\":\"faiss\",\"count\":0,\"chunks\":[]}";
        RagService service = new RagService(mockWebClientReturning(json));

        List<Map<String, Object>> result = service.retrieve("totally unrelated query", 3);

        assertTrue(result.isEmpty());
    }

    // ── RAG service unreachable entirely (connection error / timeout) ─────────
    // Must never throw — the caller (AgentService) has no try/catch of its
    // own around ragService.retrieve(); RagService itself is the boundary
    // that turns any failure into "no evidence" rather than an exception.
    @Test
    void retrieve_returnsEmptyList_onConnectionFailure() {
        WebClient client = Mockito.mock(WebClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(client.post().uri(any(String.class)).bodyValue(any())
                .retrieve().bodyToMono(String.class)
                .timeout(any(Duration.class)).block())
                .thenThrow(new RuntimeException("Connection refused"));
        RagService service = new RagService(client);

        List<Map<String, Object>> result = service.retrieve("any query", 3);

        assertTrue(result.isEmpty(),
                "RAG failure must never throw — must degrade to empty evidence");
    }

    private WebClient mockWebClientReturning(String jsonResponse) {
        WebClient client = Mockito.mock(WebClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(client.post().uri(any(String.class)).bodyValue(any())
                .retrieve().bodyToMono(String.class)
                .timeout(any(Duration.class)).block())
                .thenReturn(jsonResponse);
        return client;
    }
}
