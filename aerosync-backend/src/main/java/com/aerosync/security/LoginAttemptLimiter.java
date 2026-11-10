package com.aerosync.security;

import com.aerosync.exception.TooManyLoginAttemptsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@code maxFailures} failed logins for
 * one username from one client address within {@code window}, further
 * attempts are refused (HTTP 429) until the oldest failure leaves the window.
 * A successful login clears the counter.
 *
 * <p>In memory and per backend instance. Behind the nginx proxy every client
 * shares the proxy's address, so the limit then effectively applies per
 * username.</p>
 */
public class LoginAttemptLimiter {

    static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public static String key(String username, String clientAddress) {
        return (username == null ? "" : username) + "|" + (clientAddress == null ? "" : clientAddress);
    }

    /** @throws TooManyLoginAttemptsException when the key is currently locked */
    public void checkAllowed(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return;
        }
        Instant now = clock.instant();
        synchronized (attempts) {
            prune(attempts, now);
            if (attempts.size() >= maxFailures) {
                Instant unlockAt = attempts.peekFirst().plus(window);
                throw new TooManyLoginAttemptsException(Math.max(1, Duration.between(now, unlockAt).toSeconds()));
            }
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (failures.size() >= MAX_TRACKED_KEYS) {
            pruneAll(now);
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts, now);
            attempts.addLast(now);
            while (attempts.size() > maxFailures) {
                attempts.removeFirst();
            }
        }
    }

    public void recordSuccess(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.removeFirst();
        }
    }

    private void pruneAll(Instant now) {
        failures.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }
}
