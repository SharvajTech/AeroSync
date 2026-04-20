package com.aerosync.service;

import com.aerosync.dto.FlightPredictionResponse;
import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightService {

    private final FlightRepository flightRepository;
    private final MLService        mlService;

    // Get all flights
    public List<Flight> getAllFlights() {
        return flightRepository.findAll();
    }

    // Get single flight
    public Flight getFlightById(String flightId) {
        return flightRepository.findByFlightId(flightId)
                .orElseThrow(() ->
                        new RuntimeException("Flight not found: " + flightId));
    }

    // Get flights by risk level
    public List<Flight> getFlightsByRisk(String riskLevel) {
        return flightRepository.findByRiskLevel(riskLevel.toUpperCase());
    }

    // Get disrupted flights
    public List<Flight> getDisruptedFlights() {
        return flightRepository.findByStatus(Flight.FlightStatus.DISRUPTED);
    }

    // Create a new flight and immediately get ML prediction
    public Flight createFlight(Flight flight) {
        flight.setCreatedAt(LocalDateTime.now());
        flight.setUpdatedAt(LocalDateTime.now());
        flight.setStatus(Flight.FlightStatus.SCHEDULED);

        // Save first to get ID
        Flight saved = flightRepository.save(flight);

        // Call ML service for prediction
        try {
            FlightPredictionResponse prediction =
                    mlService.predict(mlService.buildRequest(saved));

            saved.setDelayProbability(prediction.getDelayProbability());
            saved.setRiskLevel(prediction.getRiskLevel());
            saved.setPredictedDelayed(prediction.getPredictedDelayed());
            saved = flightRepository.save(saved);

            log.info("Flight {} created with risk level: {}",
                    saved.getFlightId(), saved.getRiskLevel());
        } catch (Exception e) {
            log.warn("ML prediction skipped for {}: {}",
                    saved.getFlightId(), e.getMessage());
        }

        return saved;
    }

    // Update flight status
    public Flight updateStatus(String flightId, Flight.FlightStatus status) {
        Flight flight = getFlightById(flightId);
        flight.setStatus(status);
        flight.setUpdatedAt(LocalDateTime.now());
        return flightRepository.save(flight);
    }

    // Run ML prediction on all existing flights
    // Called on startup to score all flights
    public void predictAllFlights() {
        List<Flight> flights = flightRepository.findAll();
        log.info("Running ML predictions on {} flights...", flights.size());

        for (Flight flight : flights) {
            try {
                FlightPredictionResponse prediction =
                        mlService.predict(mlService.buildRequest(flight));
                flight.setDelayProbability(prediction.getDelayProbability());
                flight.setRiskLevel(prediction.getRiskLevel());
                flight.setPredictedDelayed(prediction.getPredictedDelayed());
                flight.setUpdatedAt(LocalDateTime.now());
                flightRepository.save(flight);
            } catch (Exception e) {
                log.warn("Prediction failed for {}", flight.getFlightId());
            }
        }
        log.info("ML predictions complete.");
    }
}