package com.aerosync.exception;

/** A requested airport, flight or other operational record does not exist (HTTP 404). */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
