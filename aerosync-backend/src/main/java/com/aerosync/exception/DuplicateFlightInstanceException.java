package com.aerosync.exception;

/** The same flight number already operates on that service date from that departure airport. */
public class DuplicateFlightInstanceException extends RuntimeException {

    public DuplicateFlightInstanceException(String message) {
        super(message);
    }
}
