package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

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

    public enum DisruptionType {
        WEATHER_DELAY, CREW_TIMEOUT, AIRCRAFT_FAULT,
        GATE_CONFLICT, CASCADING_DELAY
    }

    public enum DisruptionStatus {
        DETECTED, RESOLVING, RESOLVED, FAILED, PROPOSED
    }
}