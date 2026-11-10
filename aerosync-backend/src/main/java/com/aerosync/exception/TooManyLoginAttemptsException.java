package com.aerosync.exception;

/** Too many failed logins for this username from this client. Mapped to HTTP 429. */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many failed login attempts. Try again in " + Math.max(1, (retryAfterSeconds + 59) / 60)
                + " minute(s).");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
