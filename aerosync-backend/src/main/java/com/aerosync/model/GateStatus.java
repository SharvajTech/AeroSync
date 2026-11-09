package com.aerosync.model;

/**
 * Operational status of a gate. Independent of the legacy {@code available}
 * flag on {@link Gate}, which tracks momentary occupancy.
 */
public enum GateStatus {
    OPEN,
    CLOSED,
    MAINTENANCE
}
