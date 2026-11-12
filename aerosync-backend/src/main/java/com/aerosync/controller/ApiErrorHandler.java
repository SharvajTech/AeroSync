package com.aerosync.controller;

import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.exception.AuthenticationRequiredException;
import com.aerosync.exception.InvalidCredentialsException;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.exception.OperationNotAvailableException;
import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.exception.TooManyLoginAttemptsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication and authorization errors for every controller, in the
 * existing error format {@code {"error": "...", "status": 403}}.
 *
 * <p>Also maps the operational-network 400/404 exceptions for controllers
 * outside {@link OperationalNetworkExceptionHandler} (they are only thrown
 * by the airport-scope checks there; existing legacy errors are unchanged).</p>
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(AccessForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(AccessForbiddenException ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage(), "forbidden", null);
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<Map<String, Object>> unauthenticated(AuthenticationRequiredException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"aerosync\"");
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage(), "authentication_required", headers);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> invalidCredentials(InvalidCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage(), "invalid_credentials", null);
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Map<String, Object>> tooManyAttempts(TooManyLoginAttemptsException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), "too_many_attempts", headers);
    }

    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> badRequest(InvalidRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), null, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), null, null);
    }

    @ExceptionHandler(OperationNotAvailableException.class)
    public ResponseEntity<Map<String, Object>> notAvailable(OperationNotAvailableException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), "not_available", null);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String code,
                                                             HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status.value());
        if (code != null) {
            body.put("code", code);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(body);
    }
}
