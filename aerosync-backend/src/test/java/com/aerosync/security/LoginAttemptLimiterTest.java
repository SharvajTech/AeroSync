package com.aerosync.security;

import com.aerosync.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptLimiterTest {

    /** Clock that tests can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);
    private final String key = LoginAttemptLimiter.key("bom.ops", "10.0.0.1");

    @Test
    void locksAfterTheConfiguredNumberOfFailures() {
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertDoesNotThrow(() -> limiter.checkAllowed(key));
        limiter.recordFailure(key);
        TooManyLoginAttemptsException ex = assertThrows(TooManyLoginAttemptsException.class,
                () -> limiter.checkAllowed(key));
        assertEquals(15 * 60, ex.getRetryAfterSeconds());
    }

    @Test
    void unlocksWhenTheOldestFailureLeavesTheWindow() {
        limiter.recordFailure(key);
        clock.advance(Duration.ofMinutes(5));
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.checkAllowed(key));
        clock.advance(Duration.ofMinutes(10));
        assertDoesNotThrow(() -> limiter.checkAllowed(key));
    }

    @Test
    void successClearsFailuresAndKeysAreIndependent() {
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        limiter.recordSuccess(key);
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertDoesNotThrow(() -> limiter.checkAllowed(key));

        String other = LoginAttemptLimiter.key("del.ops", "10.0.0.1");
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(other);
        }
        assertThrows(TooManyLoginAttemptsException.class, () -> limiter.checkAllowed(other));
        assertDoesNotThrow(() -> limiter.checkAllowed(key));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LoginAttemptLimiter(0, Duration.ofMinutes(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new LoginAttemptLimiter(1, Duration.ZERO, clock));
    }
}
