package com.aerosync.controller;

import com.aerosync.model.Flight;
import com.aerosync.security.AirportScope;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.service.FlightService;
import com.aerosync.service.ScheduleGeneratorService;
import com.aerosync.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Legacy flight endpoints. Stage 3: limited to the signed-in user's airports;
 * a flight is visible to its origin and destination airports and can be
 * changed only by users of its departure airport with an operator role.
 */
@Slf4j
@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class FlightController {

    private final FlightService flightService;
    private final ScheduleGeneratorService scheduleGeneratorService;
    private final WeatherService weatherService;

    // POST /api/flights/generate-schedule
    @PostMapping("/generate-schedule")
    public ResponseEntity<String> generateSchedule(AuthenticatedUser user) {
        user.requireOperatorRole();
        scheduleGeneratorService.generateDailySchedule(user.scope());
        return ResponseEntity.ok(
                "Daily schedule generated with live weather for "
                        + "BOM, DEL, BLR, HYD");
    }

    // GET /api/flights/weather/{airportCode}
    @GetMapping("/weather/{airportCode}")
    public ResponseEntity<Map<String, Object>> getWeather(
            AuthenticatedUser user,
            @PathVariable String airportCode) {
        user.scope().requireAirport(airportCode);
        int score = weatherService.getWeatherScore(
                airportCode.toUpperCase());
        return ResponseEntity.ok(Map.of(
                "airport_code", airportCode.toUpperCase(),
                "weather_score", score,
                "description",
                score == 3 ? "Severe — operations affected"
                        : score == 2 ? "Moderate — delays likely"
                        : score == 1 ? "Light — minor impact" : "Clear"
        ));
    }

    // GET /api/flights/weather/all
    @GetMapping("/weather/all")
    public ResponseEntity<Map<String, Integer>> getAllWeather(AuthenticatedUser user) {
        Map<String, Integer> visible = new TreeMap<>();
        weatherService.getWeatherForAllAirports().forEach((airport, score) -> {
            if (user.scope().includes(airport)) {
                visible.put(airport, score);
            }
        });
        return ResponseEntity.ok(visible);
    }

    // GET /api/flights?airport=BOM — the user's flights (optionally one of their airports)
    @GetMapping
    public ResponseEntity<List<Flight>> getAllFlights(
            AuthenticatedUser user,
            @RequestParam(name = "airport", required = false) String airport) {
        AirportScope scope = user.scope().narrowTo(AirportScope.parseCsv(airport));
        return ResponseEntity.ok(flightService.getFlights(scope));
    }

    // GET /api/flights/{flightId}
    @GetMapping("/{flightId}")
    public ResponseEntity<Flight> getFlight(AuthenticatedUser user, @PathVariable String flightId) {
        return ResponseEntity.ok(flightService.getVisibleFlight(flightId, user.scope()));
    }

    // GET /api/flights/risk/{level} — filter by LOW/MEDIUM/HIGH
    @GetMapping("/risk/{level}")
    public ResponseEntity<List<Flight>> getByRisk(
            AuthenticatedUser user,
            @PathVariable String level) {
        return ResponseEntity.ok(flightService.getFlightsByRisk(level, user.scope()));
    }

    // GET /api/flights/disrupted
    @GetMapping("/disrupted")
    public ResponseEntity<List<Flight>> getDisrupted(AuthenticatedUser user) {
        return ResponseEntity.ok(flightService.getDisruptedFlights(user.scope()));
    }

    // POST /api/flights — create flight + auto ML prediction (departure airport must be the user's)
    @PostMapping
    public ResponseEntity<Flight> createFlight(AuthenticatedUser user, @RequestBody Flight flight) {
        user.requireOperatorRole();
        return ResponseEntity.ok(flightService.createFlight(flight, user.scope()));
    }

    // PATCH /api/flights/{flightId}/status
    @PatchMapping("/{flightId}/status")
    public ResponseEntity<Flight> updateStatus(
            AuthenticatedUser user,
            @PathVariable String flightId,
            @RequestParam Flight.FlightStatus status) {
        user.requireOperatorRole();
        return ResponseEntity.ok(
                flightService.updateStatus(flightId, status, user.scope()));
    }

    // POST /api/flights/predict-all — score the user's departing flights via ML
    @PostMapping("/predict-all")
    public ResponseEntity<String> predictAll(AuthenticatedUser user) {
        user.requireOperatorRole();
        int count = flightService.predictFlights(user.scope());
        return ResponseEntity.ok("ML predictions complete for " + count + " flight(s) departing from "
                + String.join(", ", user.scope().airports()));
    }
}