package com.aerosync.graph;

import java.util.List;

/** A proposed flight dependency violates the operational dependency rules. */
public class InvalidDependencyException extends RuntimeException {

    private final List<String> violations;

    public InvalidDependencyException(List<String> violations) {
        super("Invalid flight dependency: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
