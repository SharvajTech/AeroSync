package com.aerosync.dto.graph;

import com.aerosync.graph.NodeType;
import com.aerosync.model.AirportStatus;

import java.util.UUID;

public record AirportNodeDto(
        String id,
        NodeType type,
        UUID airportId,
        String iataCode,
        String airportName,
        String city,
        String timezone,
        double latitude,
        double longitude,
        AirportStatus status) implements GraphNodeDto {
}
