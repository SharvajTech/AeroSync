package com.aerosync.controller;

import com.aerosync.dto.auth.LoginRequest;
import com.aerosync.dto.auth.LoginResponse;
import com.aerosync.dto.auth.UserProfileDto;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.service.AuthService;
import com.aerosync.service.AuthService.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and the current user. {@code POST /api/auth/login} is public; every
 * other endpoint of the backend needs the returned bearer token.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/login   Body: { "username": "bom.ops", "password": "..." }
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody(required = false) LoginRequest body,
                                               HttpServletRequest request) {
        if (body == null) {
            throw new InvalidRequestParameterException("Username and password are required");
        }
        LoginResult result = authService.login(body.username(), body.password(), request.getRemoteAddr());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponse(result.token().token(), "Bearer", result.token().expiresAt(),
                        result.profile()));
    }

    // GET /api/auth/me — the signed-in user with their authorized airports
    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> me(AuthenticatedUser user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.profile(user));
    }
}
