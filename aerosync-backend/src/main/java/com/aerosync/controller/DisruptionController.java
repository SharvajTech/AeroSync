package com.aerosync.controller;

import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.service.DisruptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/disruptions")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class DisruptionController {

    private final DisruptionService disruptionService;

    // GET /api/disruptions
    @GetMapping
    public ResponseEntity<List<Disruption>> getAll() {
        return ResponseEntity.ok(disruptionService.getAllDisruptions());
    }

    // GET /api/disruptions/active
    @GetMapping("/active")
    public ResponseEntity<List<Disruption>> getActive() {
        return ResponseEntity.ok(disruptionService.getActiveDisruptions());
    }

    // POST /api/disruptions/trigger
    // Body: { "flightId": "AI-202", "type": "WEATHER_DELAY",
    //         "delayMinutes": 90 }
    @PostMapping("/trigger")
    public ResponseEntity<Disruption> trigger(
            @RequestParam String flightId,
            @RequestParam DisruptionType type,
            @RequestParam int delayMinutes) {
        return ResponseEntity.ok(
                disruptionService.triggerDisruption(
                        flightId, type, delayMinutes));
    }

    // POST /api/disruptions/simulate
    // Triggers a random disruption on a random HIGH risk flight
    @PostMapping("/simulate")
    public ResponseEntity<Disruption> simulate() {
        return ResponseEntity.ok(disruptionService.simulateRandom());
    }
}