package com.aerosync.exception;

/**
 * A write refers to an airport, flight or aircraft that does not exist, or
 * the referenced records are inconsistent with each other.
 */
public class InvalidReferenceException extends RuntimeException {

    public InvalidReferenceException(String message) {
        super(message);
    }
}
