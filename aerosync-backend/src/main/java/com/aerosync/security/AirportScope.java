package com.aerosync.security;

import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.exception.InvalidRequestParameterException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The airports a request may see and act on, derived from the authenticated
 * user (never from request parameters).
 *
 * <p>Rules used throughout the backend:</p>
 * <ul>
 *   <li><b>View</b> a flight: its origin <em>or</em> destination is in scope
 *       (both stations handle it).</li>
 *   <li><b>Act</b> on a flight (status, disruptions, resolutions, gate and
 *       crew changes): its origin is in scope, because the departure station
 *       owns the gate and crew dispatch.</li>
 *   <li>Airport-level data (operations board, airport graph): the airport is
 *       in scope.</li>
 * </ul>
 * Client-supplied airport codes are only ever used to <em>narrow</em> a scope
 * ({@link #narrowTo}), and every code must already be in scope.
 */
public final class AirportScope {

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");
    private static final AirportScope UNRESTRICTED = new AirportScope(Collections.emptySortedSet(), true);

    private final Set<String> airports;
    private final boolean unrestricted;

    private AirportScope(Set<String> airports, boolean unrestricted) {
        this.airports = airports;
        this.unrestricted = unrestricted;
    }

    /** Scope of exactly these airports (codes are trimmed and upper-cased; blanks ignored). */
    public static AirportScope of(Collection<String> iataCodes) {
        TreeSet<String> codes = new TreeSet<>();
        if (iataCodes != null) {
            for (String code : iataCodes) {
                String normalized = normalize(code);
                if (normalized != null) {
                    codes.add(normalized);
                }
            }
        }
        return new AirportScope(Collections.unmodifiableSortedSet(codes), false);
    }

    public static AirportScope of(String... iataCodes) {
        return of(List.of(iataCodes));
    }

    /**
     * Every airport. Only for system work (scheduled jobs, internal callers
     * and tests); never build this from a request.
     */
    public static AirportScope unrestricted() {
        return UNRESTRICTED;
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }

    /** The airport codes, sorted; empty for {@link #unrestricted()}. */
    public Set<String> airports() {
        return airports;
    }

    public boolean includes(String iataCode) {
        String normalized = normalize(iataCode);
        return normalized != null && (unrestricted || airports.contains(normalized));
    }

    /** A flight is visible when it departs from or arrives at an airport in scope. */
    public boolean canView(String originIata, String destinationIata) {
        return includes(originIata) || includes(destinationIata);
    }

    /** A flight can be acted on when it departs from an airport in scope. */
    public boolean canOperate(String originIata) {
        return includes(originIata);
    }

    public void requireAirport(String iataCode) {
        if (!includes(iataCode)) {
            throw new AccessForbiddenException("You are not authorized for airport " + describe(iataCode));
        }
    }

    public void requireView(String originIata, String destinationIata, String subject) {
        if (!canView(originIata, destinationIata)) {
            throw new AccessForbiddenException(subject + " operates between " + describe(originIata) + " and "
                    + describe(destinationIata) + ", outside your authorized airports");
        }
    }

    public void requireOperate(String originIata, String subject) {
        if (!canOperate(originIata)) {
            throw new AccessForbiddenException(subject + " departs from " + describe(originIata)
                    + "; only users authorized for that airport can act on it");
        }
    }

    /**
     * Narrows this scope to the requested airports. An empty or null request
     * keeps the scope unchanged. Every requested code must be a valid IATA
     * code (else 400) that is already in scope (else 403).
     */
    public AirportScope narrowTo(Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return this;
        }
        List<String> codes = new ArrayList<>(requested.size());
        for (String code : requested) {
            String normalized = normalize(code);
            if (normalized == null || !IATA.matcher(normalized).matches()) {
                throw new InvalidRequestParameterException(
                        "Invalid airport code '" + code + "': expected three letters, e.g. BOM");
            }
            requireAirport(normalized);
            codes.add(normalized);
        }
        return of(codes);
    }

    /** Splits a comma-separated parameter ("BOM,DEL") into codes; null or blank gives an empty list. */
    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                codes.add(part.trim());
            }
        }
        return codes;
    }

    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /** Echoes a code in error messages only when it looks like an IATA code. */
    private static String describe(String code) {
        String normalized = normalize(code);
        return normalized != null && IATA.matcher(normalized).matches() ? normalized : "(unknown)";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AirportScope scope && scope.unrestricted == unrestricted && scope.airports.equals(airports);
    }

    @Override
    public int hashCode() {
        return airports.hashCode() * 31 + (unrestricted ? 1 : 0);
    }

    @Override
    public String toString() {
        return unrestricted ? "AirportScope[ALL]" : "AirportScope" + airports;
    }
}
