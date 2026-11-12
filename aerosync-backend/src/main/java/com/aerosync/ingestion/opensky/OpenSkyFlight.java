package com.aerosync.ingestion.opensky;

/**
 * One flight of {@code GET /flights/departure} (also returned by the other
 * {@code /flights} endpoints). OpenSky derives these from tracks in a nightly
 * batch: the airports and times are its estimates, and there is no timetable.
 *
 * @param icao24              transponder address, lower-case hex
 * @param firstSeen           Unix time the aircraft was first seen: estimated departure
 * @param estDepartureAirport ICAO code of the estimated departure airport; null if not identified
 * @param lastSeen            Unix time the aircraft was last seen: estimated arrival
 * @param estArrivalAirport   ICAO code of the estimated arrival airport; null if not identified
 * @param callsign            trimmed callsign; null when none was received
 */
public record OpenSkyFlight(
        String icao24,
        Long firstSeen,
        String estDepartureAirport,
        Long lastSeen,
        String estArrivalAirport,
        String callsign) {
}
