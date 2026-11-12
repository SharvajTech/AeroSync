package com.aerosync.ingestion.opensky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns OpenSky JSON into records. Tolerant by design: a missing or oddly
 * typed field becomes null and a malformed entry is dropped and counted, so
 * one bad record never fails a whole response.
 */
public final class OpenSkyResponseParser {

    /** Documented state vectors have 17 fields (18 with {@code extended=1}). */
    static final int MIN_STATE_FIELDS = 17;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenSkyResponseParser() {
    }

    /** @throws IllegalArgumentException when the body is not the documented object */
    public static OpenSkyStates parseStates(String body) {
        JsonNode root = read(body);
        if (root == null || !root.isObject() || !root.has("time")) {
            throw new IllegalArgumentException("states response is not an object with a time field");
        }
        long time = root.path("time").asLong(0);
        List<OpenSkyStateVector> states = new ArrayList<>();
        int malformed = 0;
        JsonNode array = root.path("states");
        if (array.isArray()) {          // "states": null means no aircraft in the box
            for (JsonNode entry : array) {
                OpenSkyStateVector state = toState(entry);
                if (state == null) {
                    malformed++;
                } else {
                    states.add(state);
                }
            }
        }
        return new OpenSkyStates(time, states, malformed);
    }

    /** @throws IllegalArgumentException when the body is not a JSON array */
    public static List<OpenSkyFlight> parseFlights(String body) {
        JsonNode root = read(body);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("flights response is not an array");
        }
        List<OpenSkyFlight> flights = new ArrayList<>();
        for (JsonNode entry : root) {
            if (!entry.isObject()) {
                continue;
            }
            flights.add(new OpenSkyFlight(
                    lowerText(entry.path("icao24")),
                    longOrNull(entry.path("firstSeen")),
                    upperText(entry.path("estDepartureAirport")),
                    longOrNull(entry.path("lastSeen")),
                    upperText(entry.path("estArrivalAirport")),
                    upperText(entry.path("callsign"))));
        }
        return flights;
    }

    /** Reads {@code access_token} and {@code expires_in} of a token answer. */
    static TokenResponse parseToken(String body) {
        JsonNode root = read(body);
        String token = root == null ? null : text(root.path("access_token"));
        if (token == null) {
            throw new IllegalArgumentException("token response has no access_token");
        }
        long expiresIn = root.path("expires_in").asLong(1800);
        return new TokenResponse(token, expiresIn > 0 ? expiresIn : 1800);
    }

    record TokenResponse(String accessToken, long expiresInSeconds) {
    }

    private static OpenSkyStateVector toState(JsonNode entry) {
        if (entry == null || !entry.isArray() || entry.size() < MIN_STATE_FIELDS) {
            return null;
        }
        String icao24 = lowerText(entry.get(0));
        Long lastContact = longOrNull(entry.get(4));
        if (icao24 == null || lastContact == null) {
            return null;
        }
        return new OpenSkyStateVector(
                icao24,
                upperText(entry.get(1)),
                text(entry.get(2)),
                longOrNull(entry.get(3)),
                lastContact,
                doubleOrNull(entry.get(5)),
                doubleOrNull(entry.get(6)),
                doubleOrNull(entry.get(7)),
                entry.get(8).isBoolean() && entry.get(8).asBoolean(),
                doubleOrNull(entry.get(9)),
                doubleOrNull(entry.get(10)),
                doubleOrNull(entry.get(11)),
                doubleOrNull(entry.get(13)),
                text(entry.get(14)),
                entry.get(16).isNumber() ? entry.get(16).asInt() : null);
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("response is not valid JSON");
        }
    }

    /** Trimmed text, or null for null, non-text and blank values. */
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String upperText(JsonNode node) {
        String value = text(node);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String lowerText(JsonNode node) {
        String value = text(node);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Long longOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static Double doubleOrNull(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }
}
