package com.aerosync.controller;

import com.aerosync.status.DependencyProbe;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public liveness endpoint for the login page and container health checks.
 * Reports only that the process answers; no data, versions or configuration.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DependencyProbe probe;

    public HealthController(DependencyProbe probe) {
        this.probe = probe;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Readiness (Stage 4): 200 when the database answers, 503 otherwise.
     * Public like the liveness check and just as silent: only UP or DOWN.
     * Details are on the authenticated {@code /api/system/status}.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean up = probe.database().up();
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
