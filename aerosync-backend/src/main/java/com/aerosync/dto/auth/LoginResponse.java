package com.aerosync.dto.auth;

import java.time.Instant;

/** Result of a successful login. */
public record LoginResponse(String token, String tokenType, Instant expiresAt, UserProfileDto user) {

    @Override
    public String toString() {
        return "LoginResponse[user=" + (user == null ? null : user.username()) + ", expiresAt=" + expiresAt + "]";
    }
}
