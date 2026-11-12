package com.aerosync.ingestion;

/** Why ingestion did not store an external record. Counted per run, never silently dropped. */
public enum RejectReason {
    /** The transponder address is missing or not six hex digits. */
    INVALID_AIRCRAFT_ID,
    /** No position (OpenSky reports null latitude/longitude when it has none). */
    NO_POSITION,
    /** Latitude or longitude outside the valid range. */
    INVALID_POSITION,
    /** The last contact is older than the accepted age: not a current state. */
    STALE,
    /** A timestamp lies in the future. */
    FUTURE_TIMESTAMP,
    /** OpenSky could not identify the departure or the arrival airport. */
    MISSING_AIRPORT,
    /** The departure or arrival airport is not part of AeroSync's network. */
    AIRPORT_NOT_IN_NETWORK,
    /** Departure and arrival airport are the same (a local or training flight). */
    SAME_AIRPORT,
    /** The callsign is missing or is not a flight number AeroSync can derive. */
    UNMAPPED_CALLSIGN,
    /** Missing times, arrival not after departure, or a block time over 20 hours. */
    INVALID_TIMES,
    /** A leg with the same flight number, date and origin exists from another source (e.g. synthetic). */
    LEG_EXISTS_FROM_OTHER_SOURCE,
    /** A second external record for a leg that was already imported. */
    DUPLICATE_LEG
}
