package com.aerosync.model;

/**
 * Lifecycle status of one operating flight instance (one flight number on one
 * service date from one departure station).
 */
public enum FlightInstanceStatus {
    SCHEDULED,
    DELAYED,
    BOARDING,
    DEPARTED,
    ARRIVED,
    CANCELLED,
    DIVERTED
}
