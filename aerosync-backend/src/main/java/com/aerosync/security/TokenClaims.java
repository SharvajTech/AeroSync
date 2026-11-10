package com.aerosync.security;

import java.time.Instant;
import java.util.UUID;

/** The verified parts of an access token the backend relies on. */
public record TokenClaims(UUID userId, String username, Instant expiresAt) {
}
