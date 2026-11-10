package com.aerosync.security;

/** A bearer token was rejected: expired, or malformed / wrongly signed / not ours. */
public class InvalidTokenException extends RuntimeException {

    public enum Reason { EXPIRED, INVALID }

    private final Reason reason;

    public InvalidTokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
