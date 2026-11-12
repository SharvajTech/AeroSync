package com.aerosync.exception;

/**
 * The request is valid and authorized, but the action does not exist for this
 * record or in the configured data mode (HTTP 409). Examples: proposing a
 * resolution for an advisory disruption, generating the synthetic schedule in
 * LIVE mode.
 */
public class OperationNotAvailableException extends RuntimeException {

    public OperationNotAvailableException(String message) {
        super(message);
    }
}
