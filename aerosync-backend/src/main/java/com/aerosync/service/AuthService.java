package com.aerosync.service;

import com.aerosync.dto.auth.UserProfileDto;
import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.exception.AuthenticationRequiredException;
import com.aerosync.exception.InvalidCredentialsException;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.model.Airport;
import com.aerosync.model.AppUser;
import com.aerosync.repository.AppUserRepository;
import com.aerosync.security.AirportScope;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.security.IssuedToken;
import com.aerosync.security.JwtUtil;
import com.aerosync.security.LoginAttemptLimiter;
import com.aerosync.security.PrincipalLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Login (BCrypt password check, attempt limiting, token issue) and loading
 * of the current user for {@link com.aerosync.security.JwtFilter}.
 */
@Service
public class AuthService implements PrincipalLookup {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** BCrypt only uses the first 72 bytes of a password; longer input is refused. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_USERNAME_LENGTH = 64;

    public record LoginResult(IssuedToken token, UserProfileDto profile) {
    }

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptLimiter attemptLimiter;
    /** Compared against when the username is unknown, so both cases take about the same time. */
    private final String timingEqualizerHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                       LoginAttemptLimiter attemptLimiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.attemptLimiter = attemptLimiter;
        this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Checks the credentials and issues a token.
     *
     * @throws InvalidRequestParameterException blank username or password (400)
     * @throws com.aerosync.exception.TooManyLoginAttemptsException too many recent failures (429)
     * @throws InvalidCredentialsException unknown user, wrong password or disabled account (401)
     * @throws AccessForbiddenException valid credentials but no airport assigned (403)
     */
    @Transactional
    public LoginResult login(String username, String password, String clientAddress) {
        String normalized = AppUser.normalizeUsername(username);
        if (normalized == null || normalized.isEmpty() || password == null || password.isEmpty()) {
            throw new InvalidRequestParameterException("Username and password are required");
        }
        String attemptKey = LoginAttemptLimiter.key(normalized, clientAddress);
        attemptLimiter.checkAllowed(attemptKey);

        boolean plausible = normalized.length() <= MAX_USERNAME_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
        Optional<AppUser> found = plausible ? users.findWithAirportsByUsername(normalized) : Optional.empty();
        boolean passwordMatches = plausible && passwordEncoder.matches(password,
                found.map(AppUser::getPasswordHash).orElse(timingEqualizerHash));

        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            attemptLimiter.recordFailure(attemptKey);
            log.info("Failed login for username '{}'", printable(normalized));
            throw new InvalidCredentialsException();
        }

        AppUser user = found.get();
        attemptLimiter.recordSuccess(attemptKey);
        if (user.getAirports().isEmpty()) {
            throw new AccessForbiddenException(
                    "Your account has no airport assignments; ask an administrator to assign an airport");
        }
        user.setLastLoginAt(Instant.now());
        AuthenticatedUser principal = toPrincipal(user);
        log.info("User '{}' signed in (airports {})", user.getUsername(), principal.scope().airports());
        return new LoginResult(jwtUtil.issue(principal), toProfile(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> findActivePrincipal(UUID userId) {
        return users.findWithAirportsByUserId(userId)
                .filter(AppUser::isEnabled)
                .map(AuthService::toPrincipal);
    }

    @Transactional(readOnly = true)
    public UserProfileDto profile(AuthenticatedUser principal) {
        return users.findWithAirportsByUserId(principal.userId())
                .filter(AppUser::isEnabled)
                .map(AuthService::toProfile)
                .orElseThrow(() -> new AuthenticationRequiredException("Your account is disabled or no longer exists"));
    }

    static AuthenticatedUser toPrincipal(AppUser user) {
        AirportScope scope = AirportScope.of(user.getAirports().stream().map(Airport::getIataCode).toList());
        return new AuthenticatedUser(user.getUserId(), user.getUsername(), user.getDisplayName(), user.getRole(), scope);
    }

    static UserProfileDto toProfile(AppUser user) {
        return new UserProfileDto(
                user.getUserId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getRole().canOperate(),
                user.getAirports().stream()
                        .sorted(Comparator.comparing(Airport::getIataCode))
                        .map(AirportOperationsService::toDto)
                        .toList());
    }

    /** Keeps log lines single-line and short for attacker-chosen usernames. */
    private static String printable(String value) {
        String cleaned = value.replaceAll("[^a-z0-9._@-]", "?");
        return cleaned.length() > MAX_USERNAME_LENGTH ? cleaned.substring(0, MAX_USERNAME_LENGTH) + "…" : cleaned;
    }
}
