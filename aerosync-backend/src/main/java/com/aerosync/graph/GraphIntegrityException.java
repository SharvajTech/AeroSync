package com.aerosync.graph;

/**
 * Thrown when stored operational data is internally inconsistent (for example
 * a flight that references an airport which does not exist). With the
 * database foreign keys in place this indicates a bug, not bad user input.
 */
public class GraphIntegrityException extends RuntimeException {

    public GraphIntegrityException(String message) {
        super(message);
    }
}
