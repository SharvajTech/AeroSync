package com.aerosync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ── RAG EVIDENCE RETRIEVAL ──────────────────────────────────────────────────
// Calls the FastAPI ML service's /rag/retrieve endpoint (aerosync-ml/main.py,
// which in turn wraps aerosync-rag/retrieval/faiss_retriever.py) for
// relevant policy/SOP chunks. Reuses the SAME mlWebClient bean MLService
// already uses — one Python service, two capabilities (predict + retrieve).
//
// This class NEVER throws. RAG evidence is supporting context for the LLM
// explanation, not a decision input — if the ML service is down, the index
// hasn't been built yet, or the corpus has nothing relevant, this returns
// an empty list and the caller (AgentService) proceeds with no evidence
// rather than failing the whole resolution. See requirement: "if LLM/RAG
// fails, the system must still return the deterministic resolution."
@Slf4j
@Service
public class RagService {

    private final WebClient    mlWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagService(@Qualifier("mlWebClient") WebClient mlWebClient) {
        this.mlWebClient = mlWebClient;
    }

    // Returns up to topK chunks, each a Map with:
    // doc_name, doc_title, section, chunk_id, text, score.
    // Empty list on ANY failure — missing index, unreachable service,
    // malformed response, or a genuinely empty result set.
    public List<Map<String, Object>> retrieve(String query, int topK) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("top_k", topK);

            String responseStr = mlWebClient
                    .post()
                    .uri("/rag/retrieve")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(8))
                    .block();

            if (responseStr == null) {
                log.warn("RAG retrieval returned null — proceeding with no evidence");
                return List.of();
            }

            JsonNode root = objectMapper.readTree(responseStr);
            if (!root.path("available").asBoolean(false)) {
                log.warn("RAG service reports unavailable (index missing or "
                        + "aerosync-rag not reachable) — proceeding with no evidence");
                return List.of();
            }

            List<Map<String, Object>> chunks = new ArrayList<>();
            for (JsonNode c : root.path("chunks")) {
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("doc_name",  c.path("doc_name").asText(""));
                chunk.put("doc_title", c.path("doc_title").asText(""));
                chunk.put("section",   c.path("section").asText(""));
                chunk.put("chunk_id",  c.path("chunk_id").asText(""));
                chunk.put("text",      c.path("text").asText(""));
                chunk.put("score",     c.path("score").asDouble(0.0));
                chunks.add(chunk);
            }
            log.info("✅ RAG retrieved {} chunk(s) for query: {}", chunks.size(), query);
            return chunks;

        } catch (Exception e) {
            log.warn("RAG retrieval failed — proceeding with no evidence: {}", e.getMessage());
            return List.of();
        }
    }
}
