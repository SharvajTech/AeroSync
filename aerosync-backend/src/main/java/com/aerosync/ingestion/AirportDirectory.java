package com.aerosync.ingestion;

import com.aerosync.ingestion.opensky.BoundingBox;

import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The airports of the operational network as ingestion needs them: lookup by
 * ICAO code (how OpenSky names airports) and by position (which airport an
 * aircraft is at or near). Built from the {@code airports} table, so only
 * airports AeroSync knows are ever matched.
 */
public final class AirportDirectory {

    /** One airport. {@code icao} is null when the code is unknown. */
    public record AirportRef(UUID airportId, String iata, String icao, ZoneId timezone,
                             double latitude, double longitude) {
    }

    /** An airport and the great-circle distance to it. */
    public record Nearest(AirportRef airport, double distanceKm) {
    }

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final List<AirportRef> airports;
    private final Map<String, AirportRef> byIcao = new HashMap<>();
    private final Map<String, AirportRef> byIata = new HashMap<>();

    public AirportDirectory(Collection<AirportRef> airports) {
        this.airports = List.copyOf(airports);
        for (AirportRef airport : this.airports) {
            byIata.put(airport.iata(), airport);
            if (airport.icao() != null) {
                byIcao.put(airport.icao(), airport);
            }
        }
    }

    public List<AirportRef> all() {
        return airports;
    }

    public boolean isEmpty() {
        return airports.isEmpty();
    }

    public Optional<AirportRef> byIcao(String icao) {
        return icao == null ? Optional.empty() : Optional.ofNullable(byIcao.get(icao.trim().toUpperCase()));
    }

    public Optional<AirportRef> byIata(String iata) {
        return iata == null ? Optional.empty() : Optional.ofNullable(byIata.get(iata.trim().toUpperCase()));
    }

    /** The closest airport within {@code maxKm} of a position, if any. */
    public Optional<Nearest> nearest(double latitude, double longitude, double maxKm) {
        Nearest best = null;
        for (AirportRef airport : airports) {
            double km = haversineKm(latitude, longitude, airport.latitude(), airport.longitude());
            if (km <= maxKm && (best == null || km < best.distanceKm())) {
                best = new Nearest(airport, km);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The smallest box containing every airport, widened by {@code marginDegrees}
     * so aircraft approaching the outermost airports are included.
     *
     * @throws IllegalStateException when there are no airports
     */
    public BoundingBox boundingBox(double marginDegrees) {
        if (airports.isEmpty()) {
            throw new IllegalStateException("No airports to build a bounding box from");
        }
        double minLat = 90;
        double maxLat = -90;
        double minLon = 180;
        double maxLon = -180;
        for (AirportRef airport : airports) {
            minLat = Math.min(minLat, airport.latitude());
            maxLat = Math.max(maxLat, airport.latitude());
            minLon = Math.min(minLon, airport.longitude());
            maxLon = Math.max(maxLon, airport.longitude());
        }
        return new BoundingBox(
                Math.max(-90, minLat - marginDegrees), Math.max(-180, minLon - marginDegrees),
                Math.min(90, maxLat + marginDegrees), Math.min(180, maxLon + marginDegrees));
    }

    /** Great-circle distance in kilometres. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
