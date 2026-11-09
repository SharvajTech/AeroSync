package com.aerosync.exception;

/** A request parameter is malformed or out of range (HTTP 400). */
public class InvalidRequestParameterException extends RuntimeException {

    public InvalidRequestParameterException(String message) {
        super(message);
    }
}
