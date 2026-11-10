package com.aerosync.controller;

import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.security.AirportScope;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.service.DisruptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Legacy disruption endpoints. Stage 3: a user sees the disruptions of flights
 * at their airports; triggering needs an operator role and the flight's
 * departure airport.
 */
@RestController
@RequestMapping("/api/disruptions")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class DisruptionController {

    private final DisruptionService disruptionService;

    // GET /api/disruptions?airport=BOM
    @GetMapping
    public ResponseEntity<List<Disruption>> getAll(
            AuthenticatedUser user,
            @RequestParam(name = "airport", required = false) String airport) {
        AirportScope scope = user.scope().narrowTo(AirportScope.parseCsv(airport));
        return ResponseEntity.ok(disruptionService.getDisruptions(scope));
    }

    // GET /api/disruptions/active?airport=BOM
    @GetMapping("/active")
    public ResponseEntity<List<Disruption>> getActive(
            AuthenticatedUser user,
            @RequestParam(name = "airport", required = false) String airport) {
        AirportScope scope = user.scope().narrowTo(AirportScope.parseCsv(airport));
        return ResponseEntity.ok(disruptionService.getActiveDisruptions(scope));
    }

    // POST /api/disruptions/trigger
    // Body: { "flightId": "AI-202", "type": "WEATHER_DELAY",
    //         "delayMinutes": 90 }
    @PostMapping("/trigger")
    public ResponseEntity<Disruption> trigger(
            AuthenticatedUser user,
            @RequestParam String flightId,
            @RequestParam DisruptionType type,
            @RequestParam int delayMinutes) {
        user.requireOperatorRole();
        return ResponseEntity.ok(
                disruptionService.triggerDisruption(
                        flightId, type, delayMinutes, user.scope()));
    }

    // POST /api/disruptions/simulate
    // Triggers a random disruption on a random HIGH risk flight
    @PostMapping("/simulate")
    public ResponseEntity<Disruption> simulate(AuthenticatedUser user) {
        user.requireOperatorRole();
        return ResponseEntity.ok(disruptionService.simulateRandom(user.scope()));
    }
}