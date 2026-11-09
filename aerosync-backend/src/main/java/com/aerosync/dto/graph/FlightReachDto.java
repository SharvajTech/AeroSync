package com.aerosync.dto.graph;

import com.aerosync.model.DependencyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A flight reached by dependency traversal. {@code depth} is the number of
 * dependency hops from the focus flight; {@code pathImpactWeight} is the
 * product of impact weights along the path that first reached it (a
 * structural indicator, not a delay prediction).
 */
public record FlightReachDto(
        String nodeId,
        UUID flightInstanceId,
        String flightNumber,
        LocalDate operatingDate,
        String originAirport,
        String destinationAirport,
        Instant scheduledDeparture,
        int depth,
        String parentNodeId,
        String viaEdgeId,
        DependencyType viaDependencyType,
        double pathImpactWeight) {
}
