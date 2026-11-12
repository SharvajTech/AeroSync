package com.aerosync.ingestion;

import com.aerosync.ingestion.AirportDirectory.AirportRef;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A validated flight leg from an external source in AeroSync terms.
 * {@code departure} and {@code arrival} are observed (first and last seen),
 * not a timetable; {@code flightNumber} is derived from the callsign.
 *
 * @param sourceRecordId stable id of the record in the source ({@code icao24:firstSeen})
 * @param serviceDate    local calendar date of the departure at the origin airport
 */
public record NormalizedFlightLeg(
        String sourceRecordId,
        String flightNumber,
        String operatingAirline,
        String callsign,
        String icao24,
        AirportRef origin,
        AirportRef destination,
        Instant departure,
        Instant arrival,
        LocalDate serviceDate) {
}
