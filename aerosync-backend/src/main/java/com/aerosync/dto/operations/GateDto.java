package com.aerosync.dto.operations;

import com.aerosync.model.GateStatus;

import java.util.List;

/** Gate of an airport. {@code gateId} is gates.id; supported types come from gates.compatible_types. */
public record GateDto(
        Long gateId,
        String terminal,
        String gateNumber,
        List<String> supportedAircraftTypes,
        GateStatus status,
        Boolean available) {
}
