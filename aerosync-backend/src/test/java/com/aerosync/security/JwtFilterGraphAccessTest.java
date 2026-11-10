package com.aerosync.security;

import com.aerosync.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtFilter} without a Spring context.
 *
 * <p>Stage 3 changed the contract tested here before: the graph and airport
 * endpoints were public for GET and now need a token like every operational
 * endpoint. Only login, health and CORS preflight stay public.</p>
 */
class JwtFilterGraphAccessTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789-abcdefghijkl";

    private final UUID activeUserId = UUID.randomUUID();
    private final UUID disabledUserId = UUID.randomUUID();
    private final AuthenticatedUser active = new AuthenticatedUser(activeUserId, "bom.ops", "BOM ops",
            UserRole.OPERATIONS_CONTROLLER, AirportScope.of("BOM"));
    private final Map<UUID, AuthenticatedUser> users = Map.of(activeUserId, active);

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, Duration.ofHours(1), Clock.systemUTC());
    private final JwtFilter filter = new JwtFilter(jwtUtil, id -> Optional.ofNullable(users.get(id)));

    private record Outcome(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private Outcome call(String method, String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(request, response, chain);
    }

    private String tokenFor(UUID userId) {
        return jwtUtil.issue(new AuthenticatedUser(userId, "someone", "Someone", UserRole.OPERATIONS_VIEWER,
                AirportScope.of("BOM"))).token();
    }

    @Test
    void loginHealthAndPreflightArePublic() throws Exception {
        assertTrue(call("POST", "/api/auth/login", null).passed());
        assertTrue(call("GET", "/api/health", null).passed());
        assertTrue(call("OPTIONS", "/api/graph/network", null).passed());
    }

    @Test
    void graphAndAirportReadsNowRequireAToken() throws Exception {
        for (String path : new String[]{"/api/graph/network", "/api/graph/airports/BOM",
                "/api/graph/flights/0b6f3c1e-8d5f-4a5e-9a51-2d3c4b5a6f70/affected",
                "/api/airports", "/api/airports/BLR/operations"}) {
            Outcome outcome = call("GET", path, null);
            assertFalse(outcome.passed(), path);
            assertEquals(401, outcome.response().getStatus(), path);
            assertTrue(outcome.response().getContentAsString().contains("\"code\":\"authentication_required\""), path);
            assertNotNull(outcome.response().getHeader("WWW-Authenticate"), path);
        }
    }

    @Test
    void legacyOperationalEndpointsAndUnknownPathsRequireAToken() throws Exception {
        for (String[] request : new String[][]{{"GET", "/api/flights"}, {"GET", "/api/disruptions"},
                {"POST", "/api/agents/resolve/1"}, {"POST", "/api/agents/approve/1"}, {"GET", "/api/auth/me"},
                {"GET", "/"}, {"GET", "/api/auth/login/extra"}, {"GET", "/api/healthz"}}) {
            Outcome outcome = call(request[0], request[1], null);
            assertFalse(outcome.passed(), request[1]);
            assertEquals(401, outcome.response().getStatus(), request[1]);
        }
    }

    @Test
    void validTokenPassesAndExposesThePrincipal() throws Exception {
        Outcome outcome = call("GET", "/api/graph/network", "Bearer " + tokenFor(activeUserId));
        assertTrue(outcome.passed());
        assertSame(active, outcome.request().getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE));
    }

    @Test
    void schemeIsCaseInsensitiveButMustBeBearer() throws Exception {
        String token = tokenFor(activeUserId);
        assertTrue(call("GET", "/api/flights", "bearer " + token).passed());
        assertFalse(call("GET", "/api/flights", "Basic " + token).passed());
        assertFalse(call("GET", "/api/flights", token).passed());
        assertFalse(call("GET", "/api/flights", "Bearer ").passed());
    }

    @Test
    void invalidTokensAreRejected() throws Exception {
        String valid = tokenFor(activeUserId);
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("A") ? "BB" : "AA");
        String otherKey = new JwtUtil("another-secret-0123456789-abcdefghijklmnop", Duration.ofHours(1),
                Clock.systemUTC()).issue(active).token();
        for (String token : new String[]{"not-a-jwt", tampered, otherKey}) {
            Outcome outcome = call("GET", "/api/flights", "Bearer " + token);
            assertFalse(outcome.passed());
            assertEquals(401, outcome.response().getStatus());
            assertTrue(outcome.response().getContentAsString().contains("\"code\":\"token_invalid\""));
            assertNull(outcome.request().getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE));
        }
    }

    @Test
    void expiredTokenIsRejectedWithItsOwnCode() throws Exception {
        Clock yesterday = Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        String expired = new JwtUtil(SECRET, Duration.ofHours(1), yesterday).issue(active).token();
        Outcome outcome = call("GET", "/api/graph/network", "Bearer " + expired);
        assertFalse(outcome.passed());
        assertEquals(401, outcome.response().getStatus());
        assertTrue(outcome.response().getContentAsString().contains("\"code\":\"token_expired\""));
    }

    @Test
    void tokenOfADisabledOrDeletedUserIsRejected() throws Exception {
        Outcome outcome = call("GET", "/api/flights", "Bearer " + tokenFor(disabledUserId));
        assertFalse(outcome.passed());
        assertEquals(401, outcome.response().getStatus());
        assertTrue(outcome.response().getContentAsString().contains("\"code\":\"account_inactive\""));
    }

    @Test
    void publicPathMatchingIsExact() {
        assertTrue(JwtFilter.isPublic("POST", "/api/auth/login"));
        assertTrue(JwtFilter.isPublic("GET", "/api/health"));
        assertTrue(JwtFilter.isPublic("OPTIONS", "/anything"));
        assertFalse(JwtFilter.isPublic("GET", "/api/auth/me"));
        assertFalse(JwtFilter.isPublic("GET", "/api/health/details"));
        assertFalse(JwtFilter.isPublic("GET", "/api/graph"));
        assertFalse(JwtFilter.isPublic("GET", "/api/airports"));
    }

    @Test
    void errorMessagesAreEscapedAsJson() {
        assertEquals("a\\\"b\\\\c\\n", JwtFilter.jsonEscape("a\"b\\c\n"));
    }
}
