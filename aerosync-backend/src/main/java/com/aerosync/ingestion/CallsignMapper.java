package com.aerosync.ingestion;

import com.aerosync.graph.NetworkIdentifiers;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a flight number from an ADS-B callsign.
 *
 * <p>Airlines transmit their ICAO three-letter designator followed by a
 * flight identifier, for example {@code IGO1454} for IndiGo 6E1454. Two
 * limits apply, and both are deliberate:</p>
 * <ul>
 *   <li>Only callsigns whose identifier is all digits are mapped. Many
 *       flights use an alphanumeric ATC callsign ({@code AIC5BH},
 *       {@code IGO89H}) that is <em>not</em> the flight number; those are
 *       left unmapped instead of guessing.</li>
 *   <li>Only airlines in the designator table are mapped. Even then the
 *       result is derived, not reported: an airline may fly a numeric
 *       callsign that differs from the ticketed flight number.</li>
 * </ul>
 */
public final class CallsignMapper {

    /** A flight number derived from a callsign. */
    public record FlightDesignator(String airline, String flightNumber) {
    }

    /**
     * ICAO to IATA designators of Indian scheduled carriers (public reference
     * data). Extend with {@code aerosync.opensky.airline-codes}.
     */
    public static final Map<String, String> DEFAULT_AIRLINES = Map.of(
            "AIC", "AI",    // Air India
            "IGO", "6E",    // IndiGo
            "SEJ", "SG",    // SpiceJet
            "AXB", "IX",    // Air India Express
            "AKJ", "QP",    // Akasa Air
            "VTI", "UK",    // Vistara
            "IAD", "I5",    // AirAsia India
            "LLR", "9I",    // Alliance Air
            "SDG", "S5");   // Star Air

    private static final Pattern NUMERIC_CALLSIGN = Pattern.compile("^([A-Z]{3})0*([1-9][0-9]{0,3})$");
    private static final Pattern ICAO_DESIGNATOR = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern IATA_DESIGNATOR = Pattern.compile(NetworkIdentifiers.AIRLINE_DESIGNATOR_REGEX);

    private final Map<String, String> airlines;

    public CallsignMapper() {
        this(DEFAULT_AIRLINES);
    }

    public CallsignMapper(Map<String, String> icaoToIata) {
        this.airlines = Map.copyOf(icaoToIata);
    }

    /**
     * The default table plus extra pairs written as {@code "ICAO=IATA,ICAO=IATA"}.
     *
     * @throws IllegalArgumentException for an entry that is not a three-letter
     *                                  ICAO and a two-character IATA designator
     */
    public static CallsignMapper withExtra(String csv) {
        Map<String, String> table = new HashMap<>(DEFAULT_AIRLINES);
        if (csv != null && !csv.isBlank()) {
            for (String entry : csv.split(",")) {
                if (entry.isBlank()) {
                    continue;
                }
                String[] pair = entry.trim().toUpperCase(Locale.ROOT).split("=");
                if (pair.length != 2 || !ICAO_DESIGNATOR.matcher(pair[0].trim()).matches()
                        || !IATA_DESIGNATOR.matcher(pair[1].trim()).matches()) {
                    throw new IllegalArgumentException("Airline codes are ICAO=IATA pairs such as AIC=AI, was '"
                            + entry.trim() + "'");
                }
                table.put(pair[0].trim(), pair[1].trim());
            }
        }
        return new CallsignMapper(table);
    }

    /** The flight number a callsign stands for, or empty when it cannot be derived safely. */
    public Optional<FlightDesignator> toFlight(String callsign) {
        if (callsign == null) {
            return Optional.empty();
        }
        Matcher matcher = NUMERIC_CALLSIGN.matcher(callsign.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String airline = airlines.get(matcher.group(1));
        if (airline == null) {
            return Optional.empty();
        }
        String flightNumber = airline + matcher.group(2);
        return NetworkIdentifiers.isValidFlightNumber(flightNumber)
                ? Optional.of(new FlightDesignator(airline, flightNumber))
                : Optional.empty();
    }
}
