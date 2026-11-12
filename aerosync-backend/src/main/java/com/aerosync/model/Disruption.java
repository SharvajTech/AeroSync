package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "disruptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Disruption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String flightId;

    @Enumerated(EnumType.STRING)
    private DisruptionType type;

    @Enumerated(EnumType.STRING)
    private DisruptionStatus status;

    private String description;
    private Integer delayMinutes;
    private Integer passengersAffected;
    private Double  costImpact;

    // Full agent resolution stored as JSONB in PostgreSQL
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String resolutionJson;

    private Long    resolutionTimeMs;
    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;

    // ── Stage 4: monitoring, provenance and decision tracking ───────────────
    // All nullable: rows written before Stage 4 keep working unchanged.

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20)
    private DisruptionSeverity severity;

    /** 0..1: how sure the detection is (1.0 for a disruption a person reported). */
    private Double confidence;

    /** Who raised it: MANUAL (a controller), SIMULATION (the simulate button) or MONITOR. */
    @Column(name = "detection_source", length = 40)
    private String detectionSource;

    /** Monitoring rule that detected it, for example WEATHER_OBSERVED; null when not from the monitor. */
    @Column(name = "detection_rule", length = 40)
    private String detectionRule;

    /** Identifies the detected event so the monitor never opens it twice. */
    @Column(name = "detection_key", length = 160)
    private String detectionKey;

    /** Origin of the flight's data (synthetic, imported or manual). */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", length = 20)
    private DataOrigin dataOrigin;

    /** IATA codes of the flight, copied at detection time; they decide who may see and act on it. */
    @Column(name = "origin_airport", length = 3)
    private String originAirport;

    @Column(name = "destination_airport", length = 3)
    private String destinationAirport;

    /**
     * Set when the disruption is about an operational-network flight
     * ({@code flight_instances}). Those are advisory: the resolution engine
     * works on the {@code flights} table only.
     */
    @Column(name = "flight_instance_id")
    private UUID flightInstanceId;

    /** Last time the monitor saw the condition still present. */
    private LocalDateTime lastObservedAt;

    private LocalDateTime proposedAt;
    private LocalDateTime approvedAt;
    @Column(name = "approved_by", length = 64)
    private String approvedBy;
    private LocalDateTime rejectedAt;
    @Column(name = "rejected_by", length = 64)
    private String rejectedBy;
    private Integer rejectionCount;

    /** Why it was closed without a resolution, for example "condition cleared". */
    private String resolutionNote;

    /** False for advisory disruptions of operational-network flights. */
    public boolean isActionable() {
        return flightInstanceId == null;
    }

    public enum DisruptionType {
        WEATHER_DELAY, CREW_TIMEOUT, AIRCRAFT_FAULT,
        GATE_CONFLICT, CASCADING_DELAY,
        // Stage 4 (detected by the disruption monitor)
        FLIGHT_DELAY, TURNAROUND_CONFLICT, MISSED_CONNECTION, AIRCRAFT_UNAVAILABLE
    }

    public enum DisruptionSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /** Statuses in which a disruption still needs attention. */
    public static final java.util.List<DisruptionStatus> OPEN_STATUSES = java.util.List.of(
            DisruptionStatus.DETECTED, DisruptionStatus.PROPOSED,
            DisruptionStatus.RESOLVING, DisruptionStatus.FAILED);

    public enum DisruptionStatus {
        DETECTED, RESOLVING, RESOLVED, FAILED, PROPOSED
    }
}