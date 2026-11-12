package com.aerosync.dto.live;

import com.aerosync.model.LiveAircraftState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;

/**
 * An aircraft observed at or near an airport. Position and motion are as the
 * source reported them; {@code flightNumber} is derived from the callsign and
 * absent when the callsign is not a flight number; {@code phase} is derived
 * from the on-ground flag and vertical rate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveAircraftDto(
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
        String phase,
        Double distanceKm,
        Instant lastContact,
        long ageSeconds,
        boolean stale,
        String dataSource) {

    /** Climb or descent faster than this (m/s) counts as climbing or descending. */
    static final double VERTICAL_RATE_THRESHOLD_MS = 1.5;

    public static LiveAircraftDto of(LiveAircraftState state, Instant now, Duration staleAfter) {
        long age = Math.max(0, Duration.between(state.getLastContact(), now).getSeconds());
        return new LiveAircraftDto(state.getIcao24(), state.getCallsign(), state.getFlightNumber(),
                state.getOperatingAirline(), state.getOriginCountry(), state.getLatitude(), state.getLongitude(),
                state.getBaroAltitudeM(), state.getGeoAltitudeM(), state.isOnGround(), state.getVelocityMs(),
                state.getTrueTrackDeg(), state.getVerticalRateMs(),
                phase(state.isOnGround(), state.getVerticalRateMs()),
                state.getNearestAirportDistanceKm(), state.getLastContact(), age,
                age > staleAfter.getSeconds(), state.getDataSource());
    }

    static String phase(boolean onGround, Double verticalRateMs) {
        if (onGround) {
            return "ON_GROUND";
        }
        if (verticalRateMs == null) {
            return "AIRBORNE";
        }
        if (verticalRateMs > VERTICAL_RATE_THRESHOLD_MS) {
            return "CLIMBING";
        }
        return verticalRateMs < -VERTICAL_RATE_THRESHOLD_MS ? "DESCENDING" : "LEVEL";
    }
}
