package com.aerosync.service;

import com.aerosync.dto.FlightPredictionRequest;
import com.aerosync.dto.FlightPredictionResponse;
import com.aerosync.model.Flight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class MLService {

    private final WebClient    mlWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Constructor injection with @Qualifier
    // tells Spring to inject the mlWebClient bean specifically
    public MLService(@Qualifier("mlWebClient") WebClient mlWebClient) {
        this.mlWebClient = mlWebClient;
    }


    // ── MAIN PREDICT METHOD ───────────────────────────────────────────────────
    // Called by FlightService for each flight.
    // Sends a Map (not a DTO) so WebClient serializes
    // keys exactly as written — snake_case matches Python expectations.
    public FlightPredictionResponse predict(FlightPredictionRequest request) {
        log.info("Calling ML service for flight: {}",
                request.getFlightId());

        try {
            // Build snake_case map manually
            // This is the fix for Spring 7 removing Jackson codec classes
            // WebClient sends Map keys exactly as written
            // So Python FastAPI receives departure_hour not departureHour
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("flight_id",
                    request.getFlightId());
            body.put("departure_hour",
                    request.getDepartureHour());
            body.put("day_of_week",
                    request.getDayOfWeek());
            body.put("month",
                    request.getMonth());
            body.put("origin_airport",
                    request.getOriginAirport());
            body.put("destination_airport",
                    request.getDestinationAirport());
            body.put("route_distance_km",
                    request.getRouteDistanceKm());
            body.put("weather_score",
                    request.getWeatherScore());
            body.put("crew_duty_hours",
                    request.getCrewDutyHours());
            body.put("historical_delay_rate",
                    request.getHistoricalDelayRate());
            body.put("aircraft_age",
                    request.getAircraftAge());
            body.put("gate_turnaround_mins",
                    request.getGateTurnaroundMins());
            body.put("aircraft_type",
                    request.getAircraftType());

            // Log exact JSON being sent — helps debug any mismatch
            log.info("Sending to Python: {}", body);

            // Send Map as body — keys stay snake_case
            String responseStr = mlWebClient
                    .post()
                    .uri("/predict")
                    .bodyValue(body)
                    .retrieve()
                    // Log 4xx errors from Python explicitly
                    .onStatus(
                            status -> status.is4xxClientError(),
                            res -> res.bodyToMono(String.class)
                                    .map(b -> {
                                        log.error("ML service 4xx: {}", b);
                                        return new RuntimeException(
                                                "ML validation error: " + b);
                                    })
                    )
                    // Log 5xx errors from Python explicitly
                    .onStatus(
                            status -> status.is5xxServerError(),
                            res -> res.bodyToMono(String.class)
                                    .map(b -> {
                                        log.error("ML service 5xx: {}", b);
                                        return new RuntimeException(
                                                "ML server error: " + b);
                                    })
                    )
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();

            if (responseStr == null) {
                log.error("ML service returned null for flight: {}",
                        request.getFlightId());
                return buildFallback(request.getFlightId());
            }

            log.info("ML raw response: {}", responseStr);

            // Parse response manually using JsonNode
            // Avoids any camelCase conversion issues with automatic mapping
            JsonNode node = objectMapper.readTree(responseStr);

            FlightPredictionResponse resp = new FlightPredictionResponse();
            resp.setFlightId(request.getFlightId());
            resp.setDelayProbability(
                    node.path("delay_probability").asDouble(0.5));
            resp.setRiskLevel(
                    node.path("risk_level").asText("MEDIUM"));
            resp.setPredictedDelayed(
                    node.path("predicted_delayed").asBoolean(false));
            resp.setConfidence(
                    node.path("confidence").asDouble(0.5));
            resp.setModelUsed(
                    node.path("model_used").asText("xgboost"));
            resp.setFeaturesUsed(
                    node.path("features_used").asInt(17));

            log.info("✅ Prediction → flight:{} risk:{} prob:{}",
                    resp.getFlightId(),
                    resp.getRiskLevel(),
                    resp.getDelayProbability());

            return resp;

        } catch (Exception e) {
            log.error("ML call failed for flight {}: {}",
                    request.getFlightId(), e.getMessage());
            return buildFallback(request.getFlightId());
        }
    }


    // ── BUILD REQUEST FROM FLIGHT ENTITY ──────────────────────────────────────
    // Converts a Flight JPA entity into a FlightPredictionRequest DTO.
    // All null checks prevent NullPointerException when fields are empty.
    public FlightPredictionRequest buildRequest(Flight flight) {
        FlightPredictionRequest req = new FlightPredictionRequest();

        req.setFlightId(
                flight.getFlightId() != null
                        ? flight.getFlightId() : "UNKNOWN");
        req.setDepartureHour(
                flight.getDepartureHour() != null
                        ? flight.getDepartureHour() : 12);
        req.setDayOfWeek(
                flight.getDayOfWeek() != null
                        ? flight.getDayOfWeek() : 1);
        req.setMonth(
                flight.getMonth() != null
                        ? flight.getMonth() : 1);
        req.setOriginAirport(
                flight.getOrigin() != null
                        ? flight.getOrigin() : "BOM");
        req.setDestinationAirport(
                flight.getDestination() != null
                        ? flight.getDestination() : "DEL");
        req.setRouteDistanceKm(
                flight.getRouteDistanceKm() != null
                        ? flight.getRouteDistanceKm() : 500.0);
        req.setWeatherScore(
                flight.getWeatherScore() != null
                        ? flight.getWeatherScore() : 1);
        req.setCrewDutyHours(
                flight.getCrewDutyHours() != null
                        ? flight.getCrewDutyHours() : 6.0);
        req.setHistoricalDelayRate(
                flight.getHistoricalDelayRate() != null
                        ? flight.getHistoricalDelayRate() : 0.3);
        req.setAircraftAge(
                flight.getAircraftAge() != null
                        ? flight.getAircraftAge() : 8);
        req.setGateTurnaroundMins(
                flight.getGateTurnaroundMins() != null
                        ? flight.getGateTurnaroundMins() : 45);
        req.setAircraftType(
                flight.getAircraftType() != null
                        ? flight.getAircraftType() : "A320");

        return req;
    }


    // ── FALLBACK RESPONSE ─────────────────────────────────────────────────────
    // Returned when ML service is unreachable or throws an error.
    // MEDIUM risk is the safe default — not too alarming, not dismissive.
    private FlightPredictionResponse buildFallback(String flightId) {
        FlightPredictionResponse fallback = new FlightPredictionResponse();
        fallback.setFlightId(flightId);
        fallback.setDelayProbability(0.5);
        fallback.setRiskLevel("MEDIUM");
        fallback.setPredictedDelayed(false);
        fallback.setConfidence(0.0);
        fallback.setModelUsed("fallback");
        fallback.setFeaturesUsed(0);
        return fallback;
    }
}