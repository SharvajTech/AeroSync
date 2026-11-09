package com.aerosync.graph;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence-independent view of one flight dependency. The scheduled
 * departures of both endpoints are carried so traversal can apply a time
 * horizon without loading every flight.
 */
public record DependencyRecord(
        UUID dependencyId,
        UUID sourceFlightId,
        UUID targetFlightId,
        DependencyType type,
        double impactWeight,
        Integer minimumConnectionMinutes,
        boolean active,
        Map<String, Object> metadata,
        Instant sourceScheduledDeparture,
        Instant targetScheduledDeparture,
        DataOrigin dataOrigin) {

    public DependencyRecord {
        // Defensive, order-preserving copy; JSON metadata may legitimately contain null values.
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
