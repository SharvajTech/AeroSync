package com.aerosync.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    // Public endpoints that don't need JWT
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/airports",
            "/api/health",
            "/actuator"
    );

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip JWT for public endpoints
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(
                    "{\"error\":\"Missing or invalid Authorization header\"}"
            );
            return;
        }

        String token = authHeader.substring(7);

        if (!jwtUtil.isTokenValid(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(
                    "{\"error\":\"Invalid or expired token\"}"
            );
            return;
        }

        // Set airport context in request
        String airport = jwtUtil.extractAirport(token);
        String role    = jwtUtil.extractRole(token);
        request.setAttribute("airport", airport);
        request.setAttribute("role",    role);

        log.debug("JWT valid — airport: {} role: {}", airport, role);
        filterChain.doFilter(request, response);
    }
}