package com.aerosync.dto.graph;

import java.util.UUID;

/**
 * Aircraft operating a flight. {@code aircraftId} and {@code registration}
 * are null while no tail is assigned; {@code aircraftType} then shows the
 * scheduled equipment.
 */
public record AircraftInfoDto(
        UUID aircraftId,
        String registration,
        String aircraftType) {
}
