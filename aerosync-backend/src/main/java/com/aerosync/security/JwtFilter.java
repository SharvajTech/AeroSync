package com.aerosync.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Single authentication entry point of the backend.
 *
 * <p>Every request needs {@code Authorization: Bearer <token>} except the
 * login endpoint, the health endpoint and CORS preflight ({@code OPTIONS})
 * requests. Paths that do not exist (for example {@code /}) are therefore
 * answered with 401 before Spring would answer 404, so nothing about the
 * route layout is revealed to anonymous callers.</p>
 *
 * <p>For a valid token the user is loaded from the database
 * ({@link PrincipalLookup}) and stored as {@link AuthenticatedUser} in the
 * request. Authorization (airport scope, role) is enforced by the services,
 * not here.</p>
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    /** Exact paths that never need a token. */
    static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/health", "/api/health/ready");

    private final JwtUtil jwtUtil;
    private final PrincipalLookup principals;

    public JwtFilter(JwtUtil jwtUtil, PrincipalLookup principals) {
        this.jwtUtil = jwtUtil;
        this.principals = principals;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (isPublic(request.getMethod(), pathWithinApplication(request))) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request.getHeader("Authorization"));
        if (token == null) {
            unauthorized(response, "authentication_required",
                    "Authentication required: sign in and send 'Authorization: Bearer <token>'");
            return;
        }

        TokenClaims claims;
        try {
            claims = jwtUtil.verify(token);
        } catch (InvalidTokenException ex) {
            boolean expired = ex.reason() == InvalidTokenException.Reason.EXPIRED;
            unauthorized(response, expired ? "token_expired" : "token_invalid", ex.getMessage());
            return;
        }

        Optional<AuthenticatedUser> principal = principals.findActivePrincipal(claims.userId());
        if (principal.isEmpty()) {
            log.info("Rejected token of disabled or deleted user {}", claims.userId());
            unauthorized(response, "account_inactive", "Your account is disabled or no longer exists");
            return;
        }

        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    static boolean isPublic(String method, String path) {
        // Browsers send CORS preflight requests without credentials; they never reach a handler's logic.
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        return PUBLIC_PATHS.contains(path);
    }

    /** The token of a {@code Bearer} Authorization header, or null when absent or not a bearer token. */
    static String bearerToken(String header) {
        if (header == null || header.length() <= 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "authentication_required".equals(code)
                ? "Bearer realm=\"aerosync\""
                : "Bearer realm=\"aerosync\", error=\"invalid_token\"");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + jsonEscape(message) + "\",\"status\":401,\"code\":\""
                + code + "\"}");
    }

    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
