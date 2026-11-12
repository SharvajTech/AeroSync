package com.aerosync.dto.graph;

import com.aerosync.model.Disruption;

import java.time.LocalDateTime;

/** The open disruption of a flight node, so the graph can highlight it. */
public record FlightDisruptionDto(
        Long disruptionId,
        Disruption.DisruptionType disruptionType,
        Disruption.DisruptionStatus status,
        Disruption.DisruptionSeverity severity,
        Double confidence,
        String detectionRule,
        Integer delayMinutes,
        LocalDateTime detectedAt) {

    public static FlightDisruptionDto of(Disruption disruption) {
        return new FlightDisruptionDto(disruption.getId(), disruption.getType(), disruption.getStatus(),
                disruption.getSeverity(), disruption.getConfidence(), disruption.getDetectionRule(),
                disruption.getDelayMinutes(), disruption.getDetectedAt());
    }
}
