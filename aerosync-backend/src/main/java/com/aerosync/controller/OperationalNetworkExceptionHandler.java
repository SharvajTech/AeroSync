package com.aerosync.controller;

import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.graph.GraphIntegrityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error responses for the operational network endpoints only (scoped with
 * {@code assignableTypes}, so existing controllers keep their behaviour).
 * Body format matches the existing API: {@code {"error": "...", "status": 404}}.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = {GraphController.class, AirportOperationsController.class})
public class OperationalNetworkExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> badRequest(InvalidRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value '" + ex.getValue() + "' for parameter '"
                + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Missing parameter '" + ex.getParameterName() + "'");
    }

    @ExceptionHandler(GraphIntegrityException.class)
    public ResponseEntity<Map<String, Object>> integrity(GraphIntegrityException ex) {
        log.error("Operational network data is inconsistent", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Operational network data is inconsistent: " + ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}
