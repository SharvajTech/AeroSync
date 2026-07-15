package com.aerosync.service;

import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private final FlightRepository     flightRepository;
    private final GateRepository       gateRepository;
    private final CrewRepository       crewRepository;
    private final DisruptionRepository disruptionRepository;
    private final DisruptionService    disruptionService;
    private final ObjectMapper         objectMapper = new ObjectMapper();
    private final WebClient            geminiWebClient;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String geminiModel;

    // ── Constructor injection ─────────────────────────────────────────────────
    // Spring injects all dependencies here automatically.
    // @Qualifier tells Spring which WebClient bean to inject
    // since we have two beans — mlWebClient and geminiWebClient
    private final ResolutionAlgorithmService resolutionAlgorithmService;
    private final CascadeService             cascadeService;

    public AgentService(
            FlightRepository flightRepository,
            GateRepository gateRepository,
            CrewRepository crewRepository,
            DisruptionRepository disruptionRepository,
            DisruptionService disruptionService,
            ResolutionAlgorithmService resolutionAlgorithmService,
            CascadeService cascadeService,
            @Qualifier("geminiWebClient") WebClient geminiWebClient) {
        this.flightRepository           = flightRepository;
        this.gateRepository             = gateRepository;
        this.crewRepository             = crewRepository;
        this.disruptionRepository       = disruptionRepository;
        this.disruptionService          = disruptionService;
        this.resolutionAlgorithmService = resolutionAlgorithmService;
        this.cascadeService             = cascadeService;
        this.geminiWebClient            = geminiWebClient;
    }


    // ── MAIN ENTRY POINT ──────────────────────────────────────────────────────
    // Called by AgentController when frontend clicks "AI Resolve"
    // Orchestrates the full resolution flow:
    // 1. Load disruption + flight from DB
    // 2. Load available gates and crew
    // 3. Call Gemini with full context
    // 4. Parse Gemini response
    // 5. Apply gate/crew changes to DB
    // 6. Save resolution as JSONB
    // 7. Return resolution to frontend
    // ── UPDATED MAIN ENTRY POINT ──────────────────────────────────────────────
// Hybrid flow:
// 1. Algorithm makes all decisions (fast, reliable, rule-based)
// 2. Gemini ONLY generates human-readable explanation
// 3. Cascade handled by CascadeService
    public Map<String, Object> resolveDisruption(Long disruptionId) {

        long startTime = System.currentTimeMillis();

        Disruption disruption = disruptionRepository
                .findById(disruptionId)
                .orElseThrow(() -> new RuntimeException(
                        "Disruption not found: " + disruptionId));

        Flight flight = flightRepository
                .findByFlightId(disruption.getFlightId())
                .orElseThrow(() -> new RuntimeException(
                        "Flight not found: " + disruption.getFlightId()));

        log.info("🤖 Hybrid Resolution — disruption: {} flight: {}",
                disruptionId, flight.getFlightId());

        // STEP 1 — Algorithm makes all decisions
        // Fast, deterministic, no API call needed
        Map<String, Object> algorithmicResolution =
                resolutionAlgorithmService
                        .resolveAlgorithmically(disruption, flight);

        log.info("✅ Algorithmic resolution complete");

        // STEP 2 — Gemini generates plain-English explanation
        // Only called for explanation — not for decisions
        // If Gemini fails, algorithmic resolution still stands
        String explanation = generateExplanation(
                algorithmicResolution, disruption, flight);
        algorithmicResolution.put("ai_explanation", explanation);

        // STEP 3 — Handle cascade (connected flights)
        Map<String, Object> cascadeResult =
                cascadeService.resolveCascade(
                        flight.getFlightId(),
                        disruption.getDelayMinutes() != null
                                ? disruption.getDelayMinutes() : 60);
        algorithmicResolution.put("cascade_result", cascadeResult);

        // STEP 4 — Add metadata
        long resolutionTimeMs = System.currentTimeMillis() - startTime;
        algorithmicResolution.put("resolution_time_ms", resolutionTimeMs);
        algorithmicResolution.put("disruption_id",      disruptionId);
        algorithmicResolution.put("flight_id",          flight.getFlightId());
        algorithmicResolution.put("resolved_at",
                LocalDateTime.now().toString());
        algorithmicResolution.put("model_used",
                geminiModel + "+algorithm");

        // STEP 5 — Persist to DB
        try {
            String json = objectMapper
                    .writeValueAsString(algorithmicResolution);
            disruptionService.markResolved(
                    disruptionId, json, resolutionTimeMs);
        } catch (Exception e) {
            log.error("Failed to persist resolution: {}", e.getMessage());
        }

        log.info("✅ Hybrid resolution complete in {}ms", resolutionTimeMs);
        return algorithmicResolution;
    }


    // Gemini is now ONLY called for explanation generation
// Not for decision making — decisions are algorithmic
    // ── EXPLANATION — purely algorithmic, zero API calls ─────────────────────
@SuppressWarnings("unchecked")
private String generateExplanation(
        Map<String, Object> resolution,
        Disruption disruption,
        Flight flight) {

    // Build a concise context string for Gemini
    // Gemini only explains — all decisions already made by algorithm
    try {
        Map<String, Object> gateRes =
            (Map<String, Object>) resolution.get("gate_resolution");
        Map<String, Object> crewRes =
            (Map<String, Object>) resolution.get("crew_resolution");
        Map<String, Object> paxRes  =
            (Map<String, Object>) resolution.get("passenger_resolution");
        List<String> actions =
            (List<String>) resolution.get("actions_taken");

        // Compact prompt — uses very few tokens
        String prompt = String.format(
            "Aviation ops brief for staff. Max 50 words. "
            + "Plain English only. No jargon.\n"
            + "Flight %s (%s→%s) has %s disruption, %d min delay.\n"
            + "Algorithm decided: %s\n"
            + "Explain what happened and what was done.",
            flight.getFlightId(),
            flight.getOrigin(),
            flight.getDestination(),
            disruption.getType().toString().replace("_", " "),
            disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes() : 0,
            actions != null
                ? String.join(", ", actions)
                : "see resolution details"
        );

        String geminiExplanation = callGeminiApi(prompt);

        // If Gemini returns valid text use it
        // Otherwise fall back to algorithmic explanation
        if (geminiExplanation != null
                && !geminiExplanation.isBlank()
                && !geminiExplanation.contains("KEEP")
                && geminiExplanation.length() > 20) {
            log.info("✅ Gemini explanation generated");
            return geminiExplanation;
        }

    } catch (Exception e) {
        log.warn("Gemini explanation failed: {} — using algorithmic",
                e.getMessage());
    }

    // Algorithmic fallback — always works, zero API cost
    return buildAlgorithmicExplanation(resolution, disruption, flight);
}


// Algorithmic explanation as fallback
@SuppressWarnings("unchecked")
private String buildAlgorithmicExplanation(
        Map<String, Object> resolution,
        Disruption disruption,
        Flight flight) {

    StringBuilder sb = new StringBuilder();
    sb.append("Flight ").append(flight.getFlightId())
      .append(" (").append(flight.getOrigin())
      .append("→").append(flight.getDestination())
      .append(") disrupted: ")
      .append(disruption.getType().toString()
              .replace("_", " ").toLowerCase())
      .append(". ");

    Map<String, Object> gateRes =
        (Map<String, Object>) resolution.get("gate_resolution");
    if (gateRes != null
            && "REASSIGNED".equals(gateRes.get("action"))) {
        sb.append("Gate → ").append(gateRes.get("new_gate")).append(". ");
    }

    Map<String, Object> crewRes =
        (Map<String, Object>) resolution.get("crew_resolution");
    if (crewRes != null
            && "SWAPPED".equals(crewRes.get("action"))) {
        sb.append("Crew ").append(crewRes.get("old_crew_id"))
          .append(" → ").append(crewRes.get("new_crew_id"))
          .append(" (").append(crewRes.get("hours_remaining"))
          .append("h left). ");
    }

    Map<String, Object> paxRes =
        (Map<String, Object>) resolution.get("passenger_resolution");
    if (paxRes != null && !"NONE".equals(paxRes.get("action"))) {
        sb.append(paxRes.get("affected_count"))
          .append(" pax ").append(
              paxRes.get("action").toString().toLowerCase())
          .append("ed. ");
    }

    Object delay = resolution.get("estimated_new_delay_mins");
    if (delay != null) {
        sb.append("Est. delay: ").append(delay).append(" min.");
    }

    return sb.toString();
}


    // ── ORCHESTRATOR AGENT ────────────────────────────────────────────────────
    // Builds the complete prompt with full airport context
    // and sends it to Gemini 2.0 Flash.
    // The prompt is structured to get consistent JSON output.
    private String callOrchestratorAgent(
            Disruption disruption,
            Flight flight,
            List<Gate> availableGates,
            List<Crew> availableCrew) {

        // Send only the most essential fields — reduces token usage by 60%
        // This means more requests fit within the per-minute quota
        String gatesContext = availableGates.stream()
                .limit(4) // send max 4 gates — enough for decision making
                .map(g -> g.getGateNumber()
                        + ":" + g.getTerminal()
                        + "(" + g.getCompatibleTypes() + ")")
                .collect(Collectors.joining(", "));

        String crewContext = availableCrew.stream()
                .limit(4) // send max 4 crew — enough for decision making
                .map(c -> c.getCrewId()
                        + ":" + c.getRole()
                        + ":duty=" + c.getDutyHoursUsed()
                        + "/" + c.getMaxDutyHours()
                        + ":certs=" + c.getCertifications())
                .collect(Collectors.joining(", "));

        double crewDuty    = flight.getCrewDutyHours()      != null ? flight.getCrewDutyHours()      : 0.0;
        String gate        = flight.getAssignedGate()        != null ? flight.getAssignedGate()        : "none";
        int    weather     = flight.getWeatherScore()        != null ? flight.getWeatherScore()        : 0;
        int    age         = flight.getAircraftAge()         != null ? flight.getAircraftAge()         : 0;
        String acType      = flight.getAircraftType()        != null ? flight.getAircraftType()        : "A320";
        int    delay       = disruption.getDelayMinutes()    != null ? disruption.getDelayMinutes()    : 0;
        int    pax         = disruption.getPassengersAffected() != null ? disruption.getPassengersAffected() : 0;
        double cost        = disruption.getCostImpact()      != null ? disruption.getCostImpact()      : 0.0;

        // Compact prompt — same information, fewer tokens
        String fullPrompt = String.format(
                "You are an aviation disruption AI. Resolve this flight disruption.\n\n"
                        + "FLIGHT: %s | %s→%s | %s age:%dy | gate:%s | crew_duty:%.1f/12h | weather:%d/3\n"
                        + "DISRUPTION: %s | delay:%dm | pax:%d | cost:$%.0f\n"
                        + "AVAILABLE GATES: %s\n"
                        + "AVAILABLE CREW: %s\n\n"
                        + "RULES: Swap crew if duty>10h. Reassign gate if conflict/tight turnaround. "
                        + "Rebook pax if delay>60m. Use DGCA 12h max duty rule. "
                        + "Pick gate compatible with %s.\n\n"
                        + "IMPORTANT: Keep ALL string values under 20 words. "  // ← add this line
                        + "Keep orchestrator_reasoning under 30 words.\n"       // ← add this line
                        + "Respond ONLY with this exact JSON structure:\n"
                        + "{"
                        + "\"orchestrator_reasoning\":\"string\","
                        + "\"severity\":\"HIGH|MEDIUM|LOW\","
                        + "\"gate_resolution\":{\"action\":\"REASSIGN|KEEP\",\"new_gate\":\"string or null\",\"reason\":\"string\"},"
                        + "\"crew_resolution\":{\"action\":\"SWAP|KEEP\",\"new_crew_id\":\"string or null\",\"reason\":\"string\"},"
                        + "\"passenger_resolution\":{\"action\":\"REBOOK|NOTIFY|NONE\",\"affected_count\":0,\"priority\":\"BUSINESS_FIRST|ALL_EQUAL\",\"reason\":\"string\"},"
                        + "\"aircraft_resolution\":{\"action\":\"SWAP|KEEP\",\"reason\":\"string\"},"
                        + "\"estimated_new_delay_mins\":0,"
                        + "\"total_cost_impact\":0,"
                        + "\"confidence_score\":0.0"
                        + "}",
                flight.getFlightId(),
                flight.getOrigin(), flight.getDestination(),
                acType, age, gate, crewDuty, weather,
                disruption.getType(), delay, pax, cost,
                gatesContext,
                crewContext,
                acType
        );

        return callGeminiApi(fullPrompt);
    }


    // ── GEMINI API CALLER ─────────────────────────────────────────────────────
    // Calls Gemini 2.0 Flash via Google's REST API.
    // Key differences from OpenAI format:
    //   - Request body uses "contents" → "parts" → "text"
    //   - API key is a URL query parameter not a header
    //   - Response is under candidates[0].content.parts[0].text
    //   - responseMimeType forces pure JSON output — no markdown fences
    private String callGeminiApi(String prompt) {
        int maxRetries   = 3;
        int retryDelayMs = 5000; // start at 5 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Calling Gemini — model: {} attempt: {}/{}",
                        geminiModel, attempt, maxRetries);

                Map<String, Object> requestBody = Map.of(
                        "contents", List.of(
                                Map.of("parts", List.of(
                                        Map.of("text", prompt)
                                ))
                        ),
                        "generationConfig", Map.of(
                                "temperature",      0.1,
                                "maxOutputTokens",  8192,
                                "topP",             0.8,
                                "responseMimeType", "application/json"
                        )
                );

                String uri = String.format("/%s:generateContent?key=%s",
                        geminiModel, geminiApiKey);

                String responseStr = geminiWebClient
                        .post()
                        .uri(uri)
                        .bodyValue(requestBody)
                        .retrieve()
                        .onStatus(
                                status -> status.value() == 429,
                                response -> response.bodyToMono(String.class)
                                        .map(body -> new RuntimeException(
                                                "RATE_LIMITED")))
                        .onStatus(
                                status -> status.is4xxClientError()
                                        && status.value() != 429,
                                response -> response.bodyToMono(String.class)
                                        .map(body -> new RuntimeException(
                                                "CLIENT_ERROR_"
                                                        + response.statusCode()
                                                        + ": " + body)))
                        .bodyToMono(String.class)
                        .timeout(java.time.Duration.ofSeconds(30))
                        .block();

                if (responseStr == null) {
                    log.error("Null response from Gemini");
                    continue;
                }

                JsonNode root = objectMapper.readTree(responseStr);

                if (root.has("error")) {
                    String code = root.path("error").path("code").asText();
                    String msg  = root.path("error").path("message").asText();
                    log.error("Gemini error — {}: {}", code, msg);

                    if ("429".equals(code) || msg.contains("quota")) {
                        log.warn("Rate limit — waiting {}ms", retryDelayMs);
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2;
                        continue;
                    }
                    break;
                }

                JsonNode candidates = root.path("candidates");
                if (candidates.isEmpty()) {
                    log.error("Empty candidates in Gemini response");
                    continue;
                }

                String content = candidates.get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();

                if (content == null || content.isBlank()) {
                    log.error("Blank content from Gemini");
                    continue;
                }

                log.info("✅ Gemini success on attempt {}", attempt);
                return content;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";

                if (msg.contains("429") || msg.contains("RATE_LIMITED")
                        || msg.contains("Too Many Requests")) {
                    log.warn("429 on attempt {}/{} — waiting {}ms",
                            attempt, maxRetries, retryDelayMs);
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(retryDelayMs);
                            retryDelayMs *= 2; // 5s → 10s → 20s
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } else {
                    log.error("Non-rate-limit Gemini error: {}", msg);
                    break;
                }
            }
        }

        log.warn("All Gemini attempts failed — returning fallback");
        return buildFallbackResolution();
    }


    // ── PARSE AND APPLY RESOLUTION ────────────────────────────────────────────
    // Takes the raw text from Gemini and:
    // 1. Cleans any stray markdown formatting
    // 2. Extracts the JSON object
    // 3. Parses it into a Java Map
    // 4. Applies gate changes to the DB
    // 5. Applies crew changes to the DB
    // 6. Returns the resolution map to the caller
    private Map<String, Object> parseAndApplyResolution(
            String rawResponse, Flight flight) {
        try {
            // Step 1 — clean markdown fences if Gemini adds them
            // Even with responseMimeType set, sometimes fences appear
            String cleaned = rawResponse
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();

            // Step 2 — extract JSON boundaries
            // Finds the outermost { and } to handle any stray text
            int start = cleaned.indexOf("{");
            int end   = cleaned.lastIndexOf("}");

            if (start == -1 || end == -1 || start >= end) {
                log.error("No valid JSON found in Gemini response: {}",
                        cleaned);
                return buildErrorResolution(
                        "No JSON found in response", rawResponse);
            }

            cleaned = cleaned.substring(start, end + 1);

            // Step 3 — parse JSON string into Java Map
            Map<String, Object> resolution =
                    objectMapper.readValue(cleaned, Map.class);

            // Step 4 — apply gate reassignment to database
            applyGateResolution(resolution, flight);

            // Step 5 — apply crew swap to database
            applyCrewResolution(resolution, flight);

            resolution.put("status", "RESOLVED");
            return resolution;

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}",
                    e.getMessage());
            log.error("Raw response was: {}", rawResponse);
            return buildErrorResolution(e.getMessage(), rawResponse);
        }
    }


    // ── APPLY GATE CHANGES TO DB ──────────────────────────────────────────────
    // Reads gate_resolution.action from the resolution map.
    // If REASSIGN — frees old gate and assigns new gate.
    // Updates flight.assignedGate in PostgreSQL.
    @SuppressWarnings("unchecked")
    private void applyGateResolution(
            Map<String, Object> resolution, Flight flight) {
        try {
            Object gateResObj = resolution.get("gate_resolution");
            if (gateResObj == null) {
                log.warn("No gate_resolution in Gemini response");
                return;
            }

            Map<String, Object> gateRes =
                    (Map<String, Object>) gateResObj;
            String action  = (String) gateRes.get("action");
            String newGate = (String) gateRes.get("new_gate");

            log.info("Gate resolution — action: {} new_gate: {}",
                    action, newGate);

            // Only apply if action is REASSIGN and a gate was specified
            if ("REASSIGN".equals(action)
                    && newGate != null
                    && !newGate.equals("null")
                    && !newGate.isBlank()) {

                // Free the old gate — mark it available again
                if (flight.getAssignedGate() != null) {
                    gateRepository.findAll().stream()
                            .filter(g -> g.getGateNumber()
                                    .equals(flight.getAssignedGate()))
                            .findFirst()
                            .ifPresent(g -> {
                                g.setAvailable(true);
                                g.setCurrentFlightId(null);
                                gateRepository.save(g);
                                log.info("Gate {} freed", g.getGateNumber());
                            });
                }

                // Assign the new gate — mark it unavailable
                gateRepository.findAll().stream()
                        .filter(g -> g.getGateNumber().equals(newGate))
                        .findFirst()
                        .ifPresent(g -> {
                            g.setAvailable(false);
                            g.setCurrentFlightId(flight.getFlightId());
                            gateRepository.save(g);
                            log.info("Gate {} assigned to {}",
                                    g.getGateNumber(),
                                    flight.getFlightId());
                        });

                // Update flight record with new gate
                flight.setAssignedGate(newGate);
                flight.setUpdatedAt(LocalDateTime.now());
                flightRepository.save(flight);

                log.info("✈ Gate reassigned for {}: {} → {}",
                        flight.getFlightId(),
                        flight.getAssignedGate(),
                        newGate);
            } else {
                log.info("Gate kept — no reassignment needed");
            }

        } catch (Exception e) {
            log.warn("Gate resolution apply failed: {}", e.getMessage());
        }
    }


    // ── APPLY CREW CHANGES TO DB ──────────────────────────────────────────────
    // Reads crew_resolution.action from the resolution map.
    // If SWAP — frees old crew member and assigns new one.
    // Validates new crew exists in DB before applying.
    // Updates flight.assignedCrewId in PostgreSQL.
    @SuppressWarnings("unchecked")
    private void applyCrewResolution(
            Map<String, Object> resolution, Flight flight) {
        try {
            Object crewResObj = resolution.get("crew_resolution");
            if (crewResObj == null) {
                log.warn("No crew_resolution in Gemini response");
                return;
            }

            Map<String, Object> crewRes =
                    (Map<String, Object>) crewResObj;
            String action    = (String) crewRes.get("action");
            String newCrewId = (String) crewRes.get("new_crew_id");

            log.info("Crew resolution — action: {} new_crew_id: {}",
                    action, newCrewId);

            // Only apply if action is SWAP and a crew ID was specified
            if ("SWAP".equals(action)
                    && newCrewId != null
                    && !newCrewId.equals("null")
                    && !newCrewId.isBlank()) {

                // Verify the new crew member actually exists in DB
                boolean crewExists = crewRepository.findAll()
                        .stream()
                        .anyMatch(c -> c.getCrewId().equals(newCrewId));

                if (!crewExists) {
                    log.warn("Crew {} not found in DB — skipping swap",
                            newCrewId);
                    return;
                }

                // Free old crew member — mark available again
                if (flight.getAssignedCrewId() != null) {
                    crewRepository.findAll().stream()
                            .filter(c -> c.getCrewId()
                                    .equals(flight.getAssignedCrewId()))
                            .findFirst()
                            .ifPresent(c -> {
                                c.setAvailable(true);
                                c.setCurrentFlightId(null);
                                crewRepository.save(c);
                                log.info("Crew {} freed",
                                        c.getCrewId());
                            });
                }

                // Assign new crew member
                crewRepository.findAll().stream()
                        .filter(c -> c.getCrewId().equals(newCrewId))
                        .findFirst()
                        .ifPresent(c -> {
                            c.setAvailable(false);
                            c.setCurrentFlightId(flight.getFlightId());
                            crewRepository.save(c);
                            log.info("Crew {} assigned to {}",
                                    c.getCrewId(),
                                    flight.getFlightId());
                        });

                // Update flight record
                flight.setAssignedCrewId(newCrewId);
                flight.setUpdatedAt(LocalDateTime.now());
                flightRepository.save(flight);

                log.info("👨‍✈️ Crew swapped for {}: {} → {}",
                        flight.getFlightId(),
                        flight.getAssignedCrewId(),
                        newCrewId);
            } else {
                log.info("Crew kept — no swap needed");
            }

        } catch (Exception e) {
            log.warn("Crew resolution apply failed: {}", e.getMessage());
        }
    }


    // ── FALLBACK RESOLUTION ───────────────────────────────────────────────────
    // Returned when Gemini API is unavailable or times out.
    // Uses plain string concatenation — NOT a text block with newlines
    // inside string values (which breaks Jackson JSON parsing).
    // This tells the operations team to handle it manually.
    private String buildFallbackResolution() {
        return "{"
                + "\"orchestrator_reasoning\": \"AI service temporarily unavailable. Manual intervention required.\","
                + "\"severity\": \"MEDIUM\","
                + "\"gate_resolution\": {"
                +   "\"action\": \"KEEP\","
                +   "\"new_gate\": null,"
                +   "\"reason\": \"Manual review required\""
                + "},"
                + "\"crew_resolution\": {"
                +   "\"action\": \"KEEP\","
                +   "\"new_crew_id\": null,"
                +   "\"reason\": \"Manual review required\""
                + "},"
                + "\"passenger_resolution\": {"
                +   "\"action\": \"NOTIFY\","
                +   "\"affected_count\": 0,"
                +   "\"priority\": \"ALL_EQUAL\","
                +   "\"reason\": \"Notify passengers of delay\""
                + "},"
                + "\"aircraft_resolution\": {"
                +   "\"action\": \"KEEP\","
                +   "\"reason\": \"Manual review required\""
                + "},"
                + "\"estimated_new_delay_mins\": 60,"
                + "\"total_cost_impact\": 15000,"
                + "\"confidence_score\": 0.0"
                + "}";
    }


    // ── ERROR RESOLUTION MAP ──────────────────────────────────────────────────
    // Returned when Gemini response cannot be parsed.
    // Includes the raw response and error message for debugging.
    private Map<String, Object> buildErrorResolution(
            String errorMessage, String rawResponse) {
        Map<String, Object> error = new HashMap<>();
        error.put("status",       "PARSE_ERROR");
        error.put("error",        errorMessage);
        error.put("raw_response", rawResponse);
        error.put("severity",     "MEDIUM");
        error.put("gate_resolution", Map.of(
                "action", "KEEP",
                "reason", "Parse error — manual review required"
        ));
        error.put("crew_resolution", Map.of(
                "action", "KEEP",
                "reason", "Parse error — manual review required"
        ));
        error.put("passenger_resolution", Map.of(
                "action",         "NOTIFY",
                "affected_count", 0,
                "reason",         "Parse error — manual review required"
        ));
        error.put("aircraft_resolution", Map.of(
                "action", "KEEP",
                "reason", "Parse error — manual review required"
        ));
        error.put("estimated_new_delay_mins", 60);
        error.put("total_cost_impact",        15000);
        error.put("confidence_score",         0.0);
        return error;
    }

    // ── PROPOSE — algorithm reasons, human approves ───────────────────────────
    // Returns a proposal without applying anything to DB.
    public Map<String, Object> proposeResolution(Long disruptionId) {
        long startTime = System.currentTimeMillis();

        Disruption disruption = disruptionRepository
                .findById(disruptionId)
                .orElseThrow(() -> new RuntimeException(
                    "Disruption not found: " + disruptionId));

        Flight flight = flightRepository
                .findByFlightId(disruption.getFlightId())
                .orElseThrow(() -> new RuntimeException(
                    "Flight not found: " + disruption.getFlightId()));

        log.info("📋 Proposing resolution for disruption {}",
                disruptionId);

        // Generate proposal — does NOT touch DB
        Map<String, Object> proposal =
            resolutionAlgorithmService.proposeOnly(disruption, flight);

        // Gemini explains the proposal in plain English
        String explanation = generateExplanation(
            proposal, disruption, flight);
        proposal.put("ai_explanation", explanation);

        // Save proposal as PROPOSED status
        try {
            disruption.setStatus(Disruption.DisruptionStatus.PROPOSED);
            disruption.setResolutionJson(
                objectMapper.writeValueAsString(proposal));
            disruptionRepository.save(disruption);
        } catch (Exception e) {
            log.error("Failed to save proposal: {}", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        proposal.put("proposal_time_ms",      elapsed);
        proposal.put("disruption_id",         disruptionId);
        proposal.put("flight_id",             flight.getFlightId());
        proposal.put("requires_approval",     true);
        proposal.put("proposed_at",
            LocalDateTime.now().toString());

        log.info("📋 Proposal ready in {}ms — awaiting human approval",
                elapsed);
        return proposal;
    }


    // ── APPROVE — human approved, now apply to DB ─────────────────────────────
    public Map<String, Object> applyApprovedResolution(
            Long disruptionId,
            Map<String, Object> approvedResolution) {

        long startTime = System.currentTimeMillis();

        Disruption disruption = disruptionRepository
                .findById(disruptionId)
                .orElseThrow(() -> new RuntimeException(
                    "Disruption not found: " + disruptionId));

        Flight flight = flightRepository
                .findByFlightId(disruption.getFlightId())
                .orElseThrow();

        log.info("✅ Human approved disruption {} — applying...",
                disruptionId);

        // NOW apply to database
        resolutionAlgorithmService
            .applyResolution(approvedResolution, flight);

        // Handle cascade for connected flights
        Map<String, Object> cascade = cascadeService.resolveCascade(
            flight.getFlightId(),
            disruption.getDelayMinutes() != null
                ? disruption.getDelayMinutes() : 60);

        approvedResolution.put("cascade_result",    cascade);
        approvedResolution.put("approved_by_human", true);
        approvedResolution.put("status",            "RESOLVED");

        long elapsed = System.currentTimeMillis() - startTime;
        disruptionService.markResolved(
            disruptionId,
            safeToJson(approvedResolution),
            elapsed);

        approvedResolution.put("resolution_time_ms", elapsed);
        approvedResolution.put("resolved_at",
            LocalDateTime.now().toString());

        log.info("✅ Approved resolution applied in {}ms", elapsed);
        return approvedResolution;
    }


    // ── REJECT — human rejected, agent re-reasons with feedback ───────────────
    public Map<String, Object> reReasonWithFeedback(
            Long disruptionId, String rejectionReason) {

        Disruption disruption = disruptionRepository
                .findById(disruptionId)
                .orElseThrow();
        Flight flight = flightRepository
                .findByFlightId(disruption.getFlightId())
                .orElseThrow();

        log.info("🔄 Re-reasoning disruption {} — reason: {}",
                disruptionId, rejectionReason);

        // Algorithm re-runs excluding rejected resource
        Map<String, Object> revised =
            resolutionAlgorithmService.resolveWithConstraint(
                disruption, flight, rejectionReason);

        // Gemini explains why revised proposal addresses rejection
        try {
            String prompt = String.format(
                "Previous resolution rejected: %s\n"
                + "Revised resolution for flight %s (%s disruption).\n"
                + "New actions: %s\n"
                + "In 40 words: explain why this revision "
                + "addresses the rejection.",
                rejectionReason,
                flight.getFlightId(),
                disruption.getType(),
                revised.get("actions_proposed")
            );
            String explanation = callGeminiApi(prompt);
            if (explanation != null && !explanation.isBlank()) {
                revised.put("ai_explanation", explanation);
            }
        } catch (Exception e) {
            revised.put("ai_explanation",
                "Revised proposal excludes rejected resource "
                + "and selects next best alternative.");
        }

        revised.put("rejection_reason",      rejectionReason);
        revised.put("status",                "PROPOSED");
        revised.put("requires_approval",     true);
        revised.put("proposed_at",
            LocalDateTime.now().toString());

        // Save revised proposal
        try {
            disruption.setResolutionJson(
                objectMapper.writeValueAsString(revised));
            disruptionRepository.save(disruption);
        } catch (Exception e) {
            log.error("Save failed: {}", e.getMessage());
        }

        return revised;
    }


    // Helper
    private String safeToJson(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map); }
        catch (Exception e) { return "{}"; }
    }
}