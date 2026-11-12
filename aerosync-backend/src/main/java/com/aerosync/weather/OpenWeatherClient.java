package com.aerosync.weather;

import com.aerosync.model.WeatherReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenWeatherMap client: current conditions ({@code /weather}) and the
 * 5 day / 3 hour forecast ({@code /forecast}) for a coordinate.
 *
 * <p>The API key travels as the {@code appid} query parameter (that is how
 * OpenWeatherMap works), so the request URL is never logged and never put
 * into an exception message.</p>
 */
public class OpenWeatherClient {

    public static final String DEFAULT_BASE_URL = "https://api.openweathermap.org/data/2.5";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public OpenWeatherClient(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    public OpenWeatherClient(String baseUrl, String apiKey, Duration timeout, HttpClient http) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = timeout;
        this.http = http;
    }

    /** False when no API key is set: nothing is fetched. */
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Measured conditions at a coordinate. */
    public WeatherReading fetchCurrent(double latitude, double longitude) {
        JsonNode root = get("/weather", latitude, longitude);
        WeatherReading reading = toReading(root, WeatherReport.Kind.OBSERVATION, "1h");
        if (reading == null) {
            throw new WeatherUnavailableException("OpenWeatherMap sent an observation without a time or condition");
        }
        return reading;
    }

    /** Forecast steps (every three hours) for a coordinate, earliest first, at most {@code maxSteps}. */
    public List<WeatherReading> fetchForecast(double latitude, double longitude, int maxSteps) {
        JsonNode root = get("/forecast", latitude, longitude);
        List<WeatherReading> readings = new ArrayList<>();
        for (JsonNode step : root.path("list")) {
            if (readings.size() >= maxSteps) {
                break;
            }
            WeatherReading reading = toReading(step, WeatherReport.Kind.FORECAST, "3h");
            if (reading != null) {
                readings.add(reading);
            }
        }
        return readings;
    }

    private JsonNode get(String path, double latitude, double longitude) {
        if (!configured()) {
            throw new WeatherUnavailableException("No OpenWeatherMap API key is configured");
        }
        String url = String.format(Locale.ROOT, "%s%s?lat=%.4f&lon=%.4f&units=metric&appid=%s",
                baseUrl, path, latitude, longitude, URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            // The exception text can contain the URL (and so the key): report the type only.
            throw new WeatherUnavailableException("OpenWeatherMap did not answer (" + ex.getClass().getSimpleName()
                    + ")");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WeatherUnavailableException("OpenWeatherMap request interrupted");
        }
        int status = response.statusCode();
        if (status == 401) {
            throw new WeatherUnavailableException("OpenWeatherMap rejected the API key (HTTP 401)");
        }
        if (status == 429) {
            throw new WeatherUnavailableException("OpenWeatherMap rate limit reached (HTTP 429)");
        }
        if (status != 200) {
            throw new WeatherUnavailableException("OpenWeatherMap answered HTTP " + status);
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw new WeatherUnavailableException("OpenWeatherMap sent a response that is not JSON");
        }
    }

    /**
     * @param precipitationKey {@code "1h"} for observations, {@code "3h"} for forecast steps
     * @return null when the entry has no timestamp or no condition
     */
    static WeatherReading toReading(JsonNode node, WeatherReport.Kind kind, String precipitationKey) {
        if (node == null || !node.path("dt").isNumber()) {
            return null;
        }
        JsonNode condition = node.path("weather").path(0);
        if (!condition.path("id").isNumber()) {
            return null;
        }
        int code = condition.path("id").asInt();
        Double wind = number(node.path("wind").path("speed"));
        Integer visibility = node.path("visibility").isNumber() ? node.path("visibility").asInt() : null;
        Double rain = number(node.path("rain").path(precipitationKey));
        Double snow = number(node.path("snow").path(precipitationKey));
        Double precipitation = rain == null && snow == null ? null
                : (rain == null ? 0 : rain) + (snow == null ? 0 : snow);
        String description = condition.path("description").isTextual()
                ? condition.path("description").asText() : null;
        if (description != null && description.length() > 120) {
            description = description.substring(0, 120);
        }
        return new WeatherReading(
                kind,
                Instant.ofEpochSecond(node.path("dt").asLong()),
                WeatherScoring.score(code, wind, visibility, precipitation),
                code,
                description,
                wind,
                visibility,
                number(node.path("main").path("temp")),
                precipitation,
                kind == WeatherReport.Kind.FORECAST ? number(node.path("pop")) : null);
    }

    private static Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }
}
