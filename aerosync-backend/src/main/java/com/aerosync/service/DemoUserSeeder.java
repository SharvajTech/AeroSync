package com.aerosync.service;

import com.aerosync.model.Airport;
import com.aerosync.model.AppUser;
import com.aerosync.model.UserRole;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * DEVELOPMENT ONLY. Creates demo accounts on startup when
 * {@code aerosync.demo-users.enabled=true} (env {@code AEROSYNC_DEMO_USERS_ENABLED}).
 *
 * <ul>
 *   <li>Accounts come from {@code aerosync.demo-users.accounts}
 *       ({@code username=IATA[,IATA...]:ROLE;...}).</li>
 *   <li>All of them get the password from {@code AEROSYNC_DEMO_PASSWORD};
 *       there is no default, so nothing is created until it is set.</li>
 *   <li>Passwords are stored as BCrypt hashes; existing accounts are never
 *       changed, so a password changed later is kept.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "aerosync.demo-users.enabled", havingValue = "true")
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);
    static final int MIN_PASSWORD_LENGTH = 8;

    /** One parsed account definition. */
    record DemoAccount(String username, List<String> airports, UserRole role) {
    }

    private final AppUserRepository users;
    private final AirportRepository airports;
    private final PasswordEncoder passwordEncoder;
    private final String accounts;
    private final String password;

    public DemoUserSeeder(AppUserRepository users, AirportRepository airports, PasswordEncoder passwordEncoder,
                          @Value("${aerosync.demo-users.accounts:}") String accounts,
                          @Value("${aerosync.demo-users.password:}") String password) {
        this.users = users;
        this.airports = airports;
        this.passwordEncoder = passwordEncoder;
        this.accounts = accounts;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.warn("Demo accounts are enabled but AEROSYNC_DEMO_PASSWORD is empty: no demo accounts created");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > AuthService.MAX_PASSWORD_BYTES) {
            log.warn("AEROSYNC_DEMO_PASSWORD must be {} to {} characters: no demo accounts created",
                    MIN_PASSWORD_LENGTH, AuthService.MAX_PASSWORD_BYTES);
            return;
        }
        List<DemoAccount> definitions;
        try {
            definitions = parse(accounts);
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring aerosync.demo-users.accounts: {}", ex.getMessage());
            return;
        }
        String hash = null;
        for (DemoAccount account : definitions) {
            if (users.existsByUsername(account.username())) {
                continue;
            }
            Set<Airport> assigned = new LinkedHashSet<>();
            for (String iata : account.airports()) {
                Optional<Airport> airport = airports.findByIataCode(iata);
                if (airport.isEmpty()) {
                    log.warn("Demo account {} skipped: airport {} does not exist", account.username(), iata);
                    assigned.clear();
                    break;
                }
                assigned.add(airport.get());
            }
            if (assigned.isEmpty()) {
                continue;
            }
            if (hash == null) {
                hash = passwordEncoder.encode(password);
            }
            users.save(AppUser.builder()
                    .username(account.username())
                    .displayName(String.join(", ", account.airports()) + " "
                            + (account.role() == UserRole.OPERATIONS_VIEWER ? "viewer" : "operations") + " (demo)")
                    .passwordHash(hash)
                    .role(account.role())
                    .enabled(true)
                    .airports(assigned)
                    .build());
            log.info("Created demo account '{}' ({}) for airports {}", account.username(), account.role(),
                    account.airports());
        }
    }

    /** Parses {@code bom.ops=BOM:OPERATIONS_CONTROLLER;network.ops=BOM,DEL:OPERATIONS_CONTROLLER}. */
    static List<DemoAccount> parse(String spec) {
        List<DemoAccount> result = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return result;
        }
        for (String entry : spec.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            int colon = trimmed.lastIndexOf(':');
            if (eq <= 0 || colon <= eq) {
                throw new IllegalArgumentException("expected username=IATA[,IATA]:ROLE, got '" + trimmed + "'");
            }
            String username = AppUser.normalizeUsername(trimmed.substring(0, eq));
            if (username.length() < 3 || username.length() > AuthService.MAX_USERNAME_LENGTH
                    || !username.matches("[a-z0-9._@-]+")) {
                throw new IllegalArgumentException("invalid username '" + username + "'");
            }
            List<String> codes = new ArrayList<>();
            for (String code : trimmed.substring(eq + 1, colon).split(",")) {
                String iata = code.trim().toUpperCase(Locale.ROOT);
                if (!iata.matches("[A-Z]{3}")) {
                    throw new IllegalArgumentException("invalid airport code '" + code.trim() + "' for " + username);
                }
                codes.add(iata);
            }
            UserRole role;
            try {
                role = UserRole.valueOf(trimmed.substring(colon + 1).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown role in '" + trimmed + "'");
            }
            result.add(new DemoAccount(username, List.copyOf(codes), role));
        }
        return result;
    }
}
