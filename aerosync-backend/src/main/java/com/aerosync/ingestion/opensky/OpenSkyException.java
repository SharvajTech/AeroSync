package com.aerosync.ingestion.opensky;

import java.time.Duration;

/** A failed OpenSky call. Messages never contain credentials or tokens. */
public class OpenSkyException extends RuntimeException {

    public enum Kind {
        /** HTTP 429: the credit budget is used up; {@link #retryAfter()} says how long to wait. */
        RATE_LIMITED,
        /** The credentials were rejected, or a fresh token was not accepted. */
        UNAUTHORIZED,
        /** HTTP 400/403/other 4xx: the request itself is wrong; retrying will not help. */
        REJECTED,
        /** Timeouts, connection failures and 5xx answers that persisted through every attempt. */
        UNAVAILABLE,
        /** A 200 answer that is not the documented JSON. */
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;
    private final Duration retryAfter;
    private final int attempts;

    public OpenSkyException(Kind kind, String message, int httpStatus, Duration retryAfter, int attempts) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.attempts = attempts;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status of the last answer, or 0 when there was none. */
    public int httpStatus() {
        return httpStatus;
    }

    /** How long OpenSky asked us to wait (rate limit); null otherwise. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public int attempts() {
        return attempts;
    }
}
