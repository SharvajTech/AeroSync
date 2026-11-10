package com.aerosync.security;

import java.time.Instant;

/** A signed access token and the moment it stops being accepted. */
public record IssuedToken(String token, Instant expiresAt) {
}
