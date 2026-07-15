package com.aerosync.controller;

import com.aerosync.model.Disruption;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:8080"})
public class AgentController {

    private final AgentService agentService;
    private final DisruptionRepository disruptionRepository;

    // Manual constructor — @RequiredArgsConstructor won't work here
    // because we need two beans injected
    public AgentController(AgentService agentService,
                           DisruptionRepository disruptionRepository) {
        this.agentService          = agentService;
        this.disruptionRepository  = disruptionRepository;
    }

    // POST /api/agents/resolve/{disruptionId}
    // Full auto-resolution — algorithm decides + Gemini explains + applies to DB
    @PostMapping("/resolve/{disruptionId}")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable Long disruptionId) {
        log.info("Resolution requested for disruption: {}", disruptionId);
        Map<String, Object> resolution =
                agentService.resolveDisruption(disruptionId);
        return ResponseEntity.ok(resolution);
    }

    // POST /api/agents/propose/{disruptionId}
    // Agent proposes — does NOT write to DB, awaits human approval
    @PostMapping("/propose/{disruptionId}")
    public ResponseEntity<Map<String, Object>> propose(
            @PathVariable Long disruptionId) {
        log.info("Proposal requested for disruption: {}", disruptionId);

        Disruption d = disruptionRepository.findById(disruptionId).orElse(null);
        if (d == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Disruption " + disruptionId + " not found"
            ));
        }
        if (d.getStatus() == Disruption.DisruptionStatus.RESOLVED) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Disruption already resolved"
            ));
        }

        Map<String, Object> proposal =
            agentService.proposeResolution(disruptionId);
        return ResponseEntity.ok(proposal);
    }

    // POST /api/agents/approve/{disruptionId}
    // Human approved — apply proposal to database now
    @PostMapping("/approve/{disruptionId}")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long disruptionId,
            @RequestBody Map<String, Object> approvedResolution) {
        log.info("Approval received for disruption: {}", disruptionId);
        Map<String, Object> result =
            agentService.applyApprovedResolution(disruptionId, approvedResolution);
        return ResponseEntity.ok(result);
    }

    // POST /api/agents/reject/{disruptionId}
    // Human rejected — agent re-reasons excluding the rejected resource
    // Body: { "reason": "CR-001 is on leave today" }
    @PostMapping("/reject/{disruptionId}")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long disruptionId,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "No reason provided");
        log.info("Rejection for disruption {}: {}", disruptionId, reason);
        Map<String, Object> revised =
            agentService.reReasonWithFeedback(disruptionId, reason);
        return ResponseEntity.ok(revised);
    }
}