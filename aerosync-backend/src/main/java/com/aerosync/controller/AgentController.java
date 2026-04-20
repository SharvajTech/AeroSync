package com.aerosync.controller;

import com.aerosync.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class AgentController {

    private final AgentService agentService;

    // POST /api/agents/resolve/{disruptionId}
    // This is the main endpoint — triggers full AI resolution
    @PostMapping("/resolve/{disruptionId}")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable Long disruptionId) {
        log.info("Resolution requested for disruption: {}", disruptionId);
        Map<String, Object> resolution =
                agentService.resolveDisruption(disruptionId);
        return ResponseEntity.ok(resolution);
    }
}