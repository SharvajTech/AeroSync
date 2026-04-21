package com.aerosync.controller;

import com.aerosync.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class AuthController {

    private final JwtUtil jwtUtil;

    // Predefined airport credentials for demo
    // In production: store hashed passwords in DB
    private static final Map<String, Map<String, String>> AIRPORTS = Map.of(
            "BOM", Map.of(
                    "name",     "Chhatrapati Shivaji International",
                    "city",     "Mumbai",
                    "password", "bom2024",
                    "role",     "OPERATIONS_CONTROLLER"
            ),
            "DEL", Map.of(
                    "name",     "Indira Gandhi International",
                    "city",     "Delhi",
                    "password", "del2024",
                    "role",     "OPERATIONS_CONTROLLER"
            ),
            "BLR", Map.of(
                    "name",     "Kempegowda International",
                    "city",     "Bangalore",
                    "password", "blr2024",
                    "role",     "OPERATIONS_CONTROLLER"
            ),
            "HYD", Map.of(
                    "name",     "Rajiv Gandhi International",
                    "city",     "Hyderabad",
                    "password", "hyd2024",
                    "role",     "OPERATIONS_CONTROLLER"
            )
    );

    // GET /api/auth/airports — list all available airports
    @GetMapping("/airports")
    public ResponseEntity<?> getAirports() {
        return ResponseEntity.ok(
                AIRPORTS.entrySet().stream()
                        .map(e -> Map.of(
                                "code", e.getKey(),
                                "name", e.getValue().get("name"),
                                "city", e.getValue().get("city")
                        ))
                        .toList()
        );
    }

    // POST /api/auth/login
    // Body: { "airport_code": "BOM", "password": "bom2024" }
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, String> body) {

        String code     = body.get("airport_code");
        String password = body.get("password");

        if (code == null || !AIRPORTS.containsKey(code)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown airport: " + code));
        }

        Map<String, String> airport = AIRPORTS.get(code);

        if (!airport.get("password").equals(password)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtUtil.generateToken(
                code, airport.get("role"));

        return ResponseEntity.ok(Map.of(
                "token",        token,
                "airport_code", code,
                "airport_name", airport.get("name"),
                "role",         airport.get("role"),
                "message",      "Login successful"
        ));
    }

    // GET /api/auth/me — get current airport from token
    @GetMapping("/me")
    public ResponseEntity<?> me(
            jakarta.servlet.http.HttpServletRequest request) {
        String airport = (String) request.getAttribute("airport");
        String role    = (String) request.getAttribute("role");
        return ResponseEntity.ok(Map.of(
                "airport", airport,
                "role",    role
        ));
    }
}