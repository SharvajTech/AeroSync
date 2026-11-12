package com.aerosync.ingestion.opensky;

/**
 * One state vector of {@code GET /states/all}, in the documented field order
 * (index in brackets). Everything except {@code icao24}, {@code lastContact}
 * and {@code onGround} can be null in real responses.
 *
 * @param icao24         [0] transponder address, lower-case hex
 * @param callsign       [1] trimmed callsign; null when none was received
 * @param originCountry  [2] country inferred from the address
 * @param timePosition   [3] Unix time of the last position update; null if none in the past 15 s
 * @param lastContact    [4] Unix time of the last message of any kind
 * @param longitude      [5] WGS-84 degrees
 * @param latitude       [6] WGS-84 degrees
 * @param baroAltitude   [7] barometric altitude in metres
 * @param onGround       [8] true when the position came from a surface report
 * @param velocity       [9] ground speed in m/s
 * @param trueTrack      [10] degrees clockwise from north
 * @param verticalRate   [11] m/s, positive when climbing
 * @param geoAltitude    [13] geometric altitude in metres
 * @param squawk         [14] transponder code
 * @param positionSource [16] 0 ADS-B, 1 ASTERIX, 2 MLAT, 3 FLARM
 */
public record OpenSkyStateVector(
        String icao24,
        String callsign,
        String originCountry,
        Long timePosition,
        Long lastContact,
        Double longitude,
        Double latitude,
        Double baroAltitude,
        boolean onGround,
        Double velocity,
        Double trueTrack,
        Double verticalRate,
        Double geoAltitude,
        String squawk,
        Integer positionSource) {
}
