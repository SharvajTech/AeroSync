package com.aerosync.dto.graph;

import com.aerosync.graph.GraphView;
import com.aerosync.datasource.DataSourceMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Response of every /api/graph endpoint. Fields that do not apply to a view
 * are omitted (for example {@code traversal} for the national network).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphResponse(
        GraphView view,
        String focusNodeId,
        LocalDate serviceDate,
        DataWindowDto dataWindow,
        boolean syntheticData,
        String dataNotice,
        TraversalDto traversal,
        GraphSummaryDto summary,
        List<GraphNodeDto> nodes,
        List<GraphEdgeDto> edges,
        DataSourceMode dataMode,
        List<String> dataSources) {
}
