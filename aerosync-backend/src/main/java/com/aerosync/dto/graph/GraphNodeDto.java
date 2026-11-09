package com.aerosync.dto.graph;

import com.aerosync.graph.NodeType;

/**
 * A node of the operational graph. Serialized by its concrete type
 * ({@link AirportNodeDto} or {@link FlightNodeDto}); {@code type} tells
 * clients which one they received.
 */
public sealed interface GraphNodeDto permits AirportNodeDto, FlightNodeDto {

    /** Stable node id: {@code airport:BOM} or {@code flight:<flight-instance-uuid>}. */
    String id();

    NodeType type();
}
