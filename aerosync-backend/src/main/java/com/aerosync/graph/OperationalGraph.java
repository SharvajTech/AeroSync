package com.aerosync.graph;

import com.aerosync.dto.graph.GraphEdgeDto;
import com.aerosync.dto.graph.GraphNodeDto;
import com.aerosync.dto.graph.GraphSummaryDto;

import java.util.List;
import java.util.Optional;

/** Immutable result of {@link GraphBuilder#build()}. */
public record OperationalGraph(
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges,
        GraphSummaryDto summary,
        boolean syntheticData) {

    public OperationalGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public Optional<GraphNodeDto> node(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    public Optional<GraphEdgeDto> edge(String edgeId) {
        return edges.stream().filter(e -> e.id().equals(edgeId)).findFirst();
    }

    public List<GraphEdgeDto> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.source().equals(nodeId)).toList();
    }

    public List<GraphEdgeDto> edgesTo(String nodeId) {
        return edges.stream().filter(e -> e.target().equals(nodeId)).toList();
    }
}
