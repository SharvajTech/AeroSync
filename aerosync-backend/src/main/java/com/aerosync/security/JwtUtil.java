package com.aerosync.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies AeroSync access tokens (HS256 JWT).
 *
 * <p>Claims: {@code sub} = user id, {@code username}, {@code role},
 * {@code airports} (IATA codes), {@code iss}, {@code iat}, {@code exp},
 * {@code jti}. The role and airports are informational: {@link JwtFilter}
 * reloads the user from the database on every request, so authorization
 * always uses the current assignment.</p>
 *
 * <p>The signing secret comes from {@code JWT_SECRET} (at least 32 bytes).
 * When it is not set, a random per-process key is used and a warning is
 * logged: tokens then stop working on restart, but nobody can forge them
 * with a published default.</p>
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    public static final String ISSUER = "aerosync-backend";
    public static final int MIN_SECRET_BYTES = 32;

    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_AIRPORTS = "airports";

    /** Example values that were published in this repository; never accepted as a real secret. */
    static final Set<String> PUBLISHED_EXAMPLE_SECRETS = Set.of(
            "THIS_IS_ONLY_FOR_LOCAL_DEVELOPMENT_123456789",
            "aerosync-secret-key-2024-very-long-key");

    private final Key signingKey;
    private final Duration tokenLifetime;
    private final Clock clock;
    private final JwtParser parser;

    @Autowired
    public JwtUtil(@Value("${jwt.secret:}") String secret,
                   @Value("${jwt.expiration:86400000}") long expirationMillis) {
        this(secret, Duration.ofMillis(expirationMillis), Clock.systemUTC());
    }

    public JwtUtil(String secret, Duration tokenLifetime, Clock clock) {
        if (tokenLifetime == null || tokenLifetime.isZero() || tokenLifetime.isNegative()) {
            throw new IllegalArgumentException("jwt.expiration must be a positive number of milliseconds");
        }
        this.tokenLifetime = tokenLifetime;
        this.clock = clock;
        this.signingKey = signingKey(secret);
        this.parser = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(ISSUER)
                .setClock(() -> Date.from(clock.instant()))
                .build();
    }

    /** Signs a token for the user, valid for the configured lifetime. */
    public IssuedToken issue(AuthenticatedUser user) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(tokenLifetime).truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setIssuer(ISSUER)
                .setSubject(user.userId().toString())
                .claim(CLAIM_USERNAME, user.username())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_AIRPORTS, List.copyOf(user.scope().airports()))
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies signature, issuer and expiry.
     *
     * @throws InvalidTokenException with {@link InvalidTokenException.Reason#EXPIRED}
     *         or {@link InvalidTokenException.Reason#INVALID}
     */
    public TokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Missing access token");
        }
        Claims claims;
        try {
            claims = parser.parseClaimsJws(token).getBody();
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException(InvalidTokenException.Reason.EXPIRED,
                    "Your session has expired; please sign in again");
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Invalid access token");
        }
        if (claims.getExpiration() == null || claims.getSubject() == null) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Invalid access token");
        }
        UUID userId;
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException(InvalidTokenException.Reason.INVALID, "Invalid access token");
        }
        Object username = claims.get(CLAIM_USERNAME);
        return new TokenClaims(userId, username instanceof String s ? s : null, claims.getExpiration().toInstant());
    }

    public Duration tokenLifetime() {
        return tokenLifetime;
    }

    static Key signingKey(String secret) {
        String trimmed = secret == null ? "" : secret.trim();
        if (trimmed.isEmpty() || PUBLISHED_EXAMPLE_SECRETS.contains(trimmed)) {
            log.warn("JWT_SECRET is not set (or is a published example value): using a random signing key for "
                    + "this process. Sessions end when the backend restarts. Set JWT_SECRET (at least {} "
                    + "characters) in .env.", MIN_SECRET_BYTES);
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            return Keys.hmacShaKeyFor(random);
        }
        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES
                    + " bytes (256 bits) for HS256; the configured value has " + bytes.length);
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
