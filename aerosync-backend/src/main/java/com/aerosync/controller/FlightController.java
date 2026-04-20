package com.aerosync.controller;

import com.aerosync.model.Flight;
import com.aerosync.service.FlightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class FlightController {

    private final FlightService flightService;

    // GET /api/flights — all flights
    @GetMapping
    public ResponseEntity<List<Flight>> getAllFlights() {
        return ResponseEntity.ok(flightService.getAllFlights());
    }

    // GET /api/flights/{flightId}
    @GetMapping("/{flightId}")
    public ResponseEntity<Flight> getFlight(@PathVariable String flightId) {
        return ResponseEntity.ok(flightService.getFlightById(flightId));
    }

    // GET /api/flights/risk/{level} — filter by LOW/MEDIUM/HIGH
    @GetMapping("/risk/{level}")
    public ResponseEntity<List<Flight>> getByRisk(
            @PathVariable String level) {
        return ResponseEntity.ok(flightService.getFlightsByRisk(level));
    }

    // GET /api/flights/disrupted
    @GetMapping("/disrupted")
    public ResponseEntity<List<Flight>> getDisrupted() {
        return ResponseEntity.ok(flightService.getDisruptedFlights());
    }

    // POST /api/flights — create flight + auto ML prediction
    @PostMapping
    public ResponseEntity<Flight> createFlight(@RequestBody Flight flight) {
        return ResponseEntity.ok(flightService.createFlight(flight));
    }

    // PATCH /api/flights/{flightId}/status
    @PatchMapping("/{flightId}/status")
    public ResponseEntity<Flight> updateStatus(
            @PathVariable String flightId,
            @RequestParam Flight.FlightStatus status) {
        return ResponseEntity.ok(
                flightService.updateStatus(flightId, status));
    }

    // POST /api/flights/predict-all — score all flights via ML
    @PostMapping("/predict-all")
    public ResponseEntity<String> predictAll() {
        flightService.predictAllFlights();
        return ResponseEntity.ok("ML predictions complete for all flights");
    }
}