package com.aerosync.service;

import com.aerosync.dto.FlightPredictionResponse;
import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightService {

    private final FlightRepository flightRepository;
    private final MLService        mlService;
    private final WeatherService   weatherService;

    public List<Flight> getAllFlights() {
        return flightRepository.findAll();
    }

    public Flight getFlightById(String flightId) {
        return flightRepository.findByFlightId(flightId)
                .orElseThrow(() ->
                    new RuntimeException("Flight not found: " + flightId));
    }

    public List<Flight> getFlightsByRisk(String riskLevel) {
        return flightRepository.findByRiskLevel(riskLevel.toUpperCase());
    }

    public List<Flight> getDisruptedFlights() {
        return flightRepository
                .findByStatus(Flight.FlightStatus.DISRUPTED);
    }

    public Flight createFlight(Flight flight) {
        flight.setCreatedAt(LocalDateTime.now());
        flight.setUpdatedAt(LocalDateTime.now());
        flight.setStatus(Flight.FlightStatus.SCHEDULED);

        // Get live weather score for origin airport
        if (flight.getOrigin() != null) {
            int liveWeather = weatherService
                    .getWeatherScore(flight.getOrigin());
            flight.setWeatherScore(liveWeather);
            log.info("Live weather for {}: score={}",
                    flight.getOrigin(), liveWeather);
        }

        Flight saved = flightRepository.save(flight);

        // ML prediction
        try {
            FlightPredictionResponse pred =
                mlService.predict(mlService.buildRequest(saved));
            saved.setDelayProbability(pred.getDelayProbability());
            saved.setRiskLevel(pred.getRiskLevel());
            saved.setPredictedDelayed(pred.getPredictedDelayed());
            saved = flightRepository.save(saved);
        } catch (Exception e) {
            log.warn("ML prediction skipped: {}", e.getMessage());
        }

        return saved;
    }

    public Flight updateStatus(String flightId,
                               Flight.FlightStatus status) {
        Flight flight = getFlightById(flightId);
        flight.setStatus(status);
        flight.setUpdatedAt(LocalDateTime.now());
        return flightRepository.save(flight);
    }

    // ── Predict all flights with live weather ─────────────────────────────────
    public void predictAllFlights() {
        List<Flight> flights = flightRepository.findAll();
        log.info("Running ML predictions on {} flights...",
                flights.size());

        // Fetch live weather for all airports first
        Map<String, Integer> liveWeather =
                weatherService.getWeatherForAllAirports();
        log.info("Live weather scores: {}", liveWeather);

        for (Flight flight : flights) {
            try {
                // Update flight weather score with live data
                if (flight.getOrigin() != null) {
                    int score = liveWeather.getOrDefault(
                        flight.getOrigin(),
                        flight.getWeatherScore() != null
                            ? flight.getWeatherScore() : 1);
                    flight.setWeatherScore(score);
                }

                FlightPredictionResponse pred =
                    mlService.predict(mlService.buildRequest(flight));
                flight.setDelayProbability(pred.getDelayProbability());
                flight.setRiskLevel(pred.getRiskLevel());
                flight.setPredictedDelayed(pred.getPredictedDelayed());
                flight.setUpdatedAt(LocalDateTime.now());
                flightRepository.save(flight);

                log.info("  {} → risk:{} prob:{}",
                        flight.getFlightId(),
                        flight.getRiskLevel(),
                        flight.getDelayProbability());
            } catch (Exception e) {
                log.warn("Prediction failed for {}: {}",
                        flight.getFlightId(), e.getMessage());
            }
        }
        log.info("✅ ML predictions complete");
    }

    // ── Auto-refresh weather + predictions every 30 minutes ──────────────────
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 60000)
    public void autoRefreshWeatherAndPredictions() {
        log.info("🔄 Auto-refreshing weather and ML predictions...");
        try {
            predictAllFlights();
        } catch (Exception e) {
            log.error("Auto-refresh failed: {}", e.getMessage());
        }
    }
}