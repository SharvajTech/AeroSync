package com.aerosync.dto.operations;

import com.aerosync.model.AirportStatus;

import java.util.UUID;

public record AirportDto(
        UUID airportId,
        String nodeId,
        String iataCode,
        String airportName,
        String city,
        String timezone,
        double latitude,
        double longitude,
        AirportStatus status) {
}
