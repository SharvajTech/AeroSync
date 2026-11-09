package com.aerosync.dto.graph;

import com.aerosync.graph.EdgeCategory;
import com.aerosync.graph.RelationshipType;

import java.util.Map;

/**
 * Directed edge. ROUTE edges connect airports and flights; DEPENDENCY edges
 * connect two flights and carry dependency attributes in {@code metadata}
 * (snake_case keys).
 */
public record GraphEdgeDto(
        String id,
        String source,
        String target,
        RelationshipType relationship,
        EdgeCategory category,
        Map<String, Object> metadata) {
}
