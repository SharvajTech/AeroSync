package com.aerosync.dto.graph;

import java.util.Map;

/**
 * Counts for one graph response. {@code omittedDependencyCount} counts
 * dependencies that touch the returned flights but whose other endpoint lies
 * outside the requested scope (for example an overnight rotation into the
 * next service date); they are left out rather than returned as dangling edges.
 */
public record GraphSummaryDto(
        int airportCount,
        int flightCount,
        int routeEdgeCount,
        int dependencyEdgeCount,
        Map<String, Integer> dependencyEdgesByType,
        int omittedDependencyCount) {
}
