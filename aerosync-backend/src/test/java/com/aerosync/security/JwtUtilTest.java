package com.aerosync.security;

import com.aerosync.model.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789-abcdefghijkl";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "bom.ops", "BOM ops",
            UserRole.OPERATIONS_CONTROLLER, AirportScope.of("BOM", "DEL"));

    private static JwtUtil at(Instant instant) {
        return new JwtUtil(SECRET, Duration.ofHours(8), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static InvalidTokenException.Reason rejection(JwtUtil util, String token) {
        return assertThrows(InvalidTokenException.class, () -> util.verify(token)).reason();
    }

    @Test
    void issuedTokenVerifiesAndCarriesIdentityAndScope() {
        IssuedToken issued = at(NOW).issue(user);
        assertEquals(NOW.plus(Duration.ofHours(8)), issued.expiresAt());

        TokenClaims claims = at(NOW.plusSeconds(60)).verify(issued.token());
        assertEquals(user.userId(), claims.userId());
        assertEquals("bom.ops", claims.username());
        assertEquals(issued.expiresAt(), claims.expiresAt());

        String payload = new String(Base64.getUrlDecoder().decode(issued.token().split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"airports\":[\"BOM\",\"DEL\"]"), payload);
        assertTrue(payload.contains("\"role\":\"OPERATIONS_CONTROLLER\""), payload);
        assertTrue(payload.contains("\"iss\":\"" + JwtUtil.ISSUER + "\""), payload);
    }

    @Test
    void expiredTokenIsRejectedAsExpired() {
        String token = at(NOW).issue(user).token();
        assertEquals(InvalidTokenException.Reason.EXPIRED, rejection(at(NOW.plus(Duration.ofHours(9))), token));
    }

    @Test
    void malformedTamperedAndForeignTokensAreInvalid() {
        JwtUtil util = at(NOW);
        String token = util.issue(user).token();
        String[] parts = token.split("\\.");

        String otherUserPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace(user.userId().toString(), UUID.randomUUID().toString())
                        .getBytes(StandardCharsets.UTF_8));
        String forgedPayload = parts[0] + "." + otherUserPayload + "." + parts[2];
        String otherKey = new JwtUtil("another-secret-0123456789-abcdefghijklmnop", Duration.ofHours(8),
                Clock.fixed(NOW, ZoneOffset.UTC)).issue(user).token();

        for (String bad : new String[]{"", "   ", "garbage", parts[0] + "." + parts[1], forgedPayload, otherKey}) {
            assertEquals(InvalidTokenException.Reason.INVALID, rejection(util, bad), bad);
        }
        assertEquals(InvalidTokenException.Reason.INVALID, rejection(util, null));
    }

    @Test
    void unsignedTokenIsInvalid() {
        String unsigned = Jwts.builder()
                .setIssuer(JwtUtil.ISSUER)
                .setSubject(user.userId().toString())
                .setExpiration(Date.from(NOW.plusSeconds(3600)))
                .compact();
        assertEquals(InvalidTokenException.Reason.INVALID, rejection(at(NOW), unsigned));
    }

    @Test
    void tokenFromAnotherIssuerOrWithoutSubjectIsInvalid() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String otherIssuer = Jwts.builder()
                .setIssuer("someone-else")
                .setSubject(user.userId().toString())
                .setExpiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        String noSubject = Jwts.builder()
                .setIssuer(JwtUtil.ISSUER)
                .setExpiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        String noExpiry = Jwts.builder()
                .setIssuer(JwtUtil.ISSUER)
                .setSubject(user.userId().toString())
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        String notAUuid = Jwts.builder()
                .setIssuer(JwtUtil.ISSUER)
                .setSubject("BOM")
                .setExpiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        for (String token : new String[]{otherIssuer, noSubject, noExpiry, notAUuid}) {
            assertEquals(InvalidTokenException.Reason.INVALID, rejection(at(NOW), token));
        }
    }

    @Test
    void shortSecretsAreRefused() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", Duration.ofHours(1), Clock.systemUTC()));
    }

    @Test
    void missingOrPublishedSecretFallsBackToARandomKey() {
        for (String secret : new String[]{null, "", "THIS_IS_ONLY_FOR_LOCAL_DEVELOPMENT_123456789"}) {
            JwtUtil first = new JwtUtil(secret, Duration.ofHours(1), Clock.systemUTC());
            JwtUtil second = new JwtUtil(secret, Duration.ofHours(1), Clock.systemUTC());
            String token = first.issue(user).token();
            assertEquals(user.userId(), first.verify(token).userId());
            // A second process (or anyone knowing the published value) cannot use the token.
            assertEquals(InvalidTokenException.Reason.INVALID, rejection(second, token));
        }
    }

    @Test
    void lifetimeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(SECRET, Duration.ZERO, Clock.systemUTC()));
    }
}
