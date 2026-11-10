package com.aerosync.exception;

/**
 * The caller is authenticated but not allowed to do this: the resource
 * belongs to an airport outside their assignment, or their role does not
 * permit the action. Mapped to HTTP 403.
 */
public class AccessForbiddenException extends RuntimeException {

    public AccessForbiddenException(String message) {
        super(message);
    }
}
