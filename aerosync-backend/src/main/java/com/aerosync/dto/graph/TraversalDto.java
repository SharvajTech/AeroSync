package com.aerosync.dto.graph;

import com.aerosync.model.DependencyType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Parameters and results of a dependency traversal. {@code upstream} is
 * omitted for the affected-flights view. {@code truncated} means the
 * {@code maxFlights} cap stopped the traversal; {@code depthLimitReached}
 * means flights at {@code maxDepth} may have further dependants.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraversalDto(
        int maxDepth,
        Integer horizonHours,
        double minImpactWeight,
        List<DependencyType> dependencyTypes,
        int maxFlights,
        boolean truncated,
        boolean depthLimitReached,
        List<FlightReachDto> upstream,
        List<FlightReachDto> downstream) {
}
