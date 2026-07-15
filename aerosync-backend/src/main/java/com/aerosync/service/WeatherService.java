package com.aerosync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WeatherService {

    @Value("${weather.api.key:}")
    private String weatherApiKey;

    @Value("${weather.api.url:https://api.openweathermap.org/data/2.5}")
    private String weatherApiUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache: airport code → {score, timestamp}
    private final Map<String, CachedWeather> cache =
            new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30 mins

    // Indian airport IATA → city for OpenWeatherMap
    private static final Map<String, String> AIRPORT_CITIES = Map.of(
        "BOM", "Mumbai,IN",
        "DEL", "Delhi,IN",
        "BLR", "Bangalore,IN",
        "HYD", "Hyderabad,IN",
        "CCU", "Kolkata,IN",
        "MAA", "Chennai,IN",
        "AMD", "Ahmedabad,IN",
        "GOI", "Goa,IN"
    );

    // ── Get weather score for airport ─────────────────────────────────────────
    // Returns 0-3 score. Cached for 30 minutes.
    // Falls back to 1 if API unavailable.
    public int getWeatherScore(String airportCode) {
        if (weatherApiKey == null || weatherApiKey.isBlank()) {
            log.debug("Weather API key not set — using default score");
            return 1;
        }

        // Check cache
        CachedWeather cached = cache.get(airportCode);
        if (cached != null
                && (System.currentTimeMillis() - cached.timestamp)
                   < CACHE_TTL_MS) {
            log.debug("Weather cache hit for {}: score={}",
                    airportCode, cached.score);
            return cached.score;
        }

        // Fetch from API
        try {
            String city = AIRPORT_CITIES.get(airportCode.toUpperCase());
            if (city == null) return 1;

            String url = weatherApiUrl + "/weather"
                    + "?q=" + city
                    + "&appid=" + weatherApiKey
                    + "&units=metric";

            String responseStr = WebClient.builder()
                    .build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .block();

            if (responseStr == null) return 1;

            JsonNode data  = objectMapper.readTree(responseStr);
            int score      = parseWeatherScore(data);

            // Cache result
            cache.put(airportCode, new CachedWeather(
                score, System.currentTimeMillis()));

            log.info("🌤 Live weather for {}: score={} ({})",
                    airportCode, score,
                    data.path("weather").get(0)
                        .path("description").asText("unknown"));

            return score;

        } catch (Exception e) {
            log.warn("Weather fetch failed for {}: {}",
                    airportCode, e.getMessage());
            return 1; // safe default
        }
    }

    // ── Parse OpenWeatherMap response to 0-3 score ───────────────────────────
    private int parseWeatherScore(JsonNode data) {
        int    weatherId  = data.path("weather").get(0)
                               .path("id").asInt(800);
        double windSpeed  = data.path("wind")
                               .path("speed").asDouble(0);
        int    visibility = data.path("visibility").asInt(10000);
        double rain1h     = data.path("rain")
                               .path("1h").asDouble(0);

        int score = 0;

        // Thunderstorm — always severe
        if (weatherId >= 200 && weatherId <= 232) score = 3;

        // Heavy rain
        else if (weatherId == 502 || weatherId == 503
                || weatherId == 504) score = 3;

        // Moderate rain
        else if (weatherId == 501
                || (weatherId >= 311 && weatherId <= 314)) score = 2;

        // Light rain or drizzle
        else if ((weatherId >= 300 && weatherId <= 321)
                || weatherId == 500) score = 1;

        // Snow
        else if (weatherId >= 600 && weatherId <= 622)
            score = rain1h > 1 ? 2 : 1;

        // Fog — critical for airports
        else if (weatherId == 741 || weatherId == 701) score = 2;

        // High wind
        if (windSpeed > 15) score = Math.max(score, 2);
        else if (windSpeed > 10) score = Math.max(score, 1);

        // Low visibility
        if (visibility < 1000) score = Math.max(score, 3);
        else if (visibility < 3000) score = Math.max(score, 2);
        else if (visibility < 5000) score = Math.max(score, 1);

        return Math.min(score, 3);
    }

    // ── Update all flights with live weather ──────────────────────────────────
    // Called by FlightService.predictAllFlights()
    // and by scheduled job every 30 minutes
    public Map<String, Integer> getWeatherForAllAirports() {
        Map<String, Integer> scores = new ConcurrentHashMap<>();
        for (String airport : AIRPORT_CITIES.keySet()) {
            scores.put(airport, getWeatherScore(airport));
        }
        return scores;
    }

    // Simple cache record
    private record CachedWeather(int score, long timestamp) {}
}