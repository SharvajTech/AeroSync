package com.aerosync.exception;

/**
 * Login failed. Deliberately one message for unknown user, wrong password
 * and disabled account, so the response does not reveal which accounts
 * exist. Mapped to HTTP 401.
 */
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "Invalid username or password";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
