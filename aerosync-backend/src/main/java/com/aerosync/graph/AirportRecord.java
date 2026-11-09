package com.aerosync.graph;

import com.aerosync.model.AirportStatus;

import java.util.UUID;

/** Persistence-independent airport data used to build graph nodes. */
public record AirportRecord(
        UUID airportId,
        String iataCode,
        String airportName,
        String city,
        String timezone,
        double latitude,
        double longitude,
        AirportStatus status) {
}
