package com.aerosync.graph;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifier formats and stable graph node / edge ids for the operational
 * network graph.
 *
 * <p>Node ids are stable across requests and environments:
 * {@code airport:BOM} for airports and {@code flight:<flight-instance-uuid>}
 * for flights. A flight number alone is never used as an id because the same
 * flight number operates on many dates.</p>
 */
public final class NetworkIdentifiers {

    /** Three upper-case letters, e.g. BOM. */
    public static final String IATA_CODE_REGEX = "^[A-Z]{3}$";

    /** IATA airline designator (two characters, not both digits), e.g. AI, 6E. */
    public static final String AIRLINE_DESIGNATOR_REGEX = "^([A-Z][A-Z0-9]|[0-9][A-Z])$";

    /** Airline designator + 1-4 digit number + optional operational suffix, e.g. AI101, 6E2043. */
    public static final String FLIGHT_NUMBER_REGEX = "^([A-Z][A-Z0-9]|[0-9][A-Z])[0-9]{1,4}[A-Z]?$";

    public static final String AIRPORT_NODE_PREFIX = "airport:";
    public static final String FLIGHT_NODE_PREFIX  = "flight:";

    private static final Pattern IATA_CODE     = Pattern.compile(IATA_CODE_REGEX);
    private static final Pattern FLIGHT_NUMBER = Pattern.compile(FLIGHT_NUMBER_REGEX);

    private NetworkIdentifiers() {
    }

    /**
     * Trims and upper-cases an IATA airport code.
     *
     * @throws IllegalArgumentException if the value is not three letters
     */
    public static String normalizeIata(String iataCode) {
        if (iataCode == null) {
            throw new IllegalArgumentException("IATA airport code is required");
        }
        String normalized = iataCode.trim().toUpperCase(Locale.ROOT);
        if (!IATA_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid IATA airport code '" + iataCode + "': expected three letters, e.g. BOM");
        }
        return normalized;
    }

    public static boolean isValidFlightNumber(String flightNumber) {
        return flightNumber != null && FLIGHT_NUMBER.matcher(flightNumber).matches();
    }

    public static String airportNodeId(String iataCode) {
        return AIRPORT_NODE_PREFIX + normalizeIata(iataCode);
    }

    public static String flightNodeId(UUID flightInstanceId) {
        if (flightInstanceId == null) {
            throw new IllegalArgumentException("Flight instance id is required");
        }
        return FLIGHT_NODE_PREFIX + flightInstanceId;
    }

    public static String departsFromEdgeId(UUID flightInstanceId) {
        return "departs_from:" + flightInstanceId;
    }

    public static String arrivesAtEdgeId(UUID flightInstanceId) {
        return "arrives_at:" + flightInstanceId;
    }

    public static String dependencyEdgeId(UUID dependencyId) {
        return "dependency:" + dependencyId;
    }

    /**
     * Accepts either a bare flight-instance UUID or a graph node id
     * ({@code flight:<uuid>}).
     *
     * @throws IllegalArgumentException if the value is not a flight instance reference
     */
    public static UUID parseFlightReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Flight instance id is required");
        }
        String value = reference.trim();
        if (value.startsWith(FLIGHT_NODE_PREFIX)) {
            value = value.substring(FLIGHT_NODE_PREFIX.length());
        }
        try {
            UUID parsed = UUID.fromString(value);
            // UUID.fromString accepts some non-canonical forms ("1-1-1-1-1");
            // require the canonical 36-character representation.
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid flight id '" + reference + "': expected a flight instance UUID or 'flight:<uuid>'."
                            + " Flight numbers are not unique across dates and cannot be used as ids.");
        }
    }
}
