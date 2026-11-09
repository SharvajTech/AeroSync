package com.aerosync.graph;

/**
 * Keeps the physical airport/route layer separate from the operational
 * flight-to-flight dependency layer.
 */
public enum EdgeCategory {
    /** Airport -> Flight (DEPARTS_FROM) and Flight -> Airport (ARRIVES_AT). */
    ROUTE,
    /** Flight -> Flight operational dependencies. */
    DEPENDENCY
}
