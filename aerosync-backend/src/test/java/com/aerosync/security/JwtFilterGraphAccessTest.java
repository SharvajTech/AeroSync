package com.aerosync.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only graph/airport endpoints are public like /api/flights; writes under them still need a token. */
class JwtFilterGraphAccessTest {

    private final JwtFilter filter = new JwtFilter(new JwtUtil());

    private MockFilterChain call(String method, String path, MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
        return chain;
    }

    @Test
    void readOnlyGraphAndAirportRequestsPassWithoutToken() throws Exception {
        for (String path : new String[]{"/api/graph/network", "/api/graph/airports/BOM",
                "/api/graph/flights/0b6f3c1e-8d5f-4a5e-9a51-2d3c4b5a6f70/affected",
                "/api/airports", "/api/airports/BLR/operations"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertNotNull(call("GET", path, response).getRequest(), path);
            assertEquals(200, response.getStatus(), path);
        }
    }

    @Test
    void writeRequestsUnderTheGraphPathsStillRequireAToken() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertNull(call(method, "/api/graph/network", response).getRequest(), method);
            assertEquals(401, response.getStatus(), method);
        }
    }

    @Test
    void prefixMatchingIsSegmentAware() {
        assertTrue(JwtFilter.isPublicReadOnly("GET", "/api/graph"));
        assertTrue(JwtFilter.isPublicReadOnly("HEAD", "/api/airports/BOM/operations"));
        assertTrue(JwtFilter.isPublicReadOnly("OPTIONS", "/api/graph/network"));
        assertFalse(JwtFilter.isPublicReadOnly("GET", "/api/graphql"));
        assertFalse(JwtFilter.isPublicReadOnly("GET", "/api/airportsx"));
        assertFalse(JwtFilter.isPublicReadOnly("POST", "/api/airports"));
    }
}
