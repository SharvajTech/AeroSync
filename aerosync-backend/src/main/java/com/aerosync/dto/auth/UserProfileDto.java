package com.aerosync.dto.auth;

import com.aerosync.dto.operations.AirportDto;

import java.util.List;
import java.util.UUID;

/** The signed-in user: identity, role and the airports they may operate. */
public record UserProfileDto(
        UUID userId,
        String username,
        String displayName,
        String role,
        boolean canOperate,
        List<AirportDto> airports) {
}
