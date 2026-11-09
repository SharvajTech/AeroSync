package com.aerosync.model;

/**
 * Operational dependency between two flight instances (source -> target).
 * These are kept separate from the physical airport/route relationships
 * (DEPARTS_FROM / ARRIVES_AT), which are derived from the schedule itself.
 */
public enum DependencyType {
    /** The same aircraft operates the source flight and then the target flight. */
    AIRCRAFT_ROTATION,
    /** Passengers on the source flight connect onto the target flight. */
    PASSENGER_CONNECTION,
    /** A crew (or part of it) moves from the source flight onto the target flight on another aircraft. */
    CREW_ROTATION,
    /** Any other operational coupling, e.g. a shared ground-handling team or slot sequencing. */
    OPERATIONAL_CONSTRAINT
}
