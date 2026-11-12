package com.aerosync.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated live aircraft state in AeroSync terms. Positions and motion are
 * exactly what the source reported; {@code flightNumber}, {@code airline} and
 * the nearest airport are derived by AeroSync and may be null.
 */
public record NormalizedAircraftState(
        String icao24,
        String callsign,
        String flightNumber,
        String operatingAirline,
        String originCountry,
        double latitude,
        double longitude,
        Double baroAltitudeM,
        Double geoAltitudeM,
        boolean onGround,
        Double velocityMs,
        Double trueTrackDeg,
        Double verticalRateMs,
        String squawk,
        Integer positionSource,
        Instant timePosition,
        Instant lastContact,
        UUID nearestAirportId,
        String nearestAirportIata,
        Double nearestAirportDistanceKm,
        Instant snapshotTime) {
}
