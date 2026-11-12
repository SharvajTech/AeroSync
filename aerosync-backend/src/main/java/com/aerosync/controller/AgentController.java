package com.aerosync.controller;

import com.aerosync.model.Disruption;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.service.AgentService;
import com.aerosync.service.DisruptionAccessService;
import com.aerosync.service.DisruptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Resolution actions on disruptions. Stage 3: every action needs an operator
 * role and the disrupted flight's departure airport (DisruptionAccessService);
 * cascades only touch flights of the user's airports.
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:8080"})
public class AgentController {

    private final AgentService agentService;
    private final DisruptionAccessService disruptionAccess;
    private final DisruptionService disruptionService;

    // Manual constructor — @RequiredArgsConstructor won't work here
    // because we need two beans injected
    public AgentController(AgentService agentService,
                           DisruptionAccessService disruptionAccess,
                           DisruptionService disruptionService) {
        this.agentService          = agentService;
        this.disruptionAccess      = disruptionAccess;
        this.disruptionService     = disruptionService;
    }

    // POST /api/agents/resolve/{disruptionId}
    // Full auto-resolution — algorithm decides + Gemini explains + applies to DB
    @PostMapping("/resolve/{disruptionId}")
    public ResponseEntity<Map<String, Object>> resolve(
            AuthenticatedUser user,
            @PathVariable Long disruptionId) {
        log.info("Resolution requested for disruption: {}", disruptionId);
        if (disruptionAccess.authorizeAction(disruptionId, user).isEmpty()) {
            return notFound(disruptionId);
        }
        Map<String, Object> resolution =
                agentService.resolveDisruption(disruptionId, user.scope());
        disruptionService.recordApproved(disruptionId, user.username());
        return ResponseEntity.ok(resolution);
    }

    // POST /api/agents/propose/{disruptionId}
    // Agent proposes — does NOT write to DB, awaits human approval
    @PostMapping("/propose/{disruptionId}")
    public ResponseEntity<Map<String, Object>> propose(
            AuthenticatedUser user,
            @PathVariable Long disruptionId) {
        log.info("Proposal requested for disruption: {}", disruptionId);

        Disruption d = disruptionAccess.authorizeAction(disruptionId, user).orElse(null);
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
        disruptionService.recordProposed(disruptionId);
        return ResponseEntity.ok(proposal);
    }

    // POST /api/agents/approve/{disruptionId}
    // Human approved — apply proposal to database now
    @PostMapping("/approve/{disruptionId}")
    public ResponseEntity<Map<String, Object>> approve(
            AuthenticatedUser user,
            @PathVariable Long disruptionId,
            @RequestBody Map<String, Object> approvedResolution) {
        log.info("Approval received for disruption: {}", disruptionId);
        if (disruptionAccess.authorizeAction(disruptionId, user).isEmpty()) {
            return notFound(disruptionId);
        }
        Map<String, Object> result =
            agentService.applyApprovedResolution(disruptionId, approvedResolution, user.scope());
        disruptionService.recordApproved(disruptionId, user.username());
        return ResponseEntity.ok(result);
    }

    // POST /api/agents/reject/{disruptionId}
    // Human rejected — agent re-reasons excluding the rejected resource
    // Body: { "reason": "CR-001 is on leave today" }
    @PostMapping("/reject/{disruptionId}")
    public ResponseEntity<Map<String, Object>> reject(
            AuthenticatedUser user,
            @PathVariable Long disruptionId,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "No reason provided");
        log.info("Rejection for disruption {}: {}", disruptionId, reason);
        if (disruptionAccess.authorizeAction(disruptionId, user).isEmpty()) {
            return notFound(disruptionId);
        }
        Map<String, Object> revised =
            agentService.reReasonWithFeedback(disruptionId, reason);
        disruptionService.recordRejected(disruptionId, user.username());
        disruptionService.recordProposed(disruptionId);
        return ResponseEntity.ok(revised);
    }

    private static ResponseEntity<Map<String, Object>> notFound(Long disruptionId) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "Disruption " + disruptionId + " not found",
                "status", 404));
    }
}
