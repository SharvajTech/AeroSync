package com.aerosync.exception;

/** No valid authenticated user for a protected request. Mapped to HTTP 401. */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
