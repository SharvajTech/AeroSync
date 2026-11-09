package com.aerosync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Directed operational dependency between two flight instances
 * (table {@code flight_dependencies}): a disruption of the source flight can
 * affect the target flight. Station/time consistency rules are enforced by
 * {@code com.aerosync.graph.DependencyRules}; the database enforces
 * referential integrity, no self-loops, weight range and no duplicate edges.
 */
@Entity
@Table(name = "flight_dependencies",
        uniqueConstraints = @UniqueConstraint(name = "uk_flight_dependencies_edge",
                columnNames = {"source_flight_id", "target_flight_id", "dependency_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightDependency {

    @Id
    @Column(name = "dependency_id", nullable = false, updatable = false)
    private UUID dependencyId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_flight_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_dependencies_source"))
    private FlightInstance sourceFlight;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_flight_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_dependencies_target"))
    private FlightInstance targetFlight;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_type", nullable = false, length = 32)
    private DependencyType dependencyType;

    /** 0..1 structural strength of the coupling (1 = a source delay fully constrains the target). */
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @Column(name = "impact_weight", nullable = false)
    private Double impactWeight;

    @Min(0)
    @Column(name = "minimum_connection_minutes")
    private Integer minimumConnectionMinutes;

    /** Free-form JSON object (jsonb in PostgreSQL). Keys are snake_case. */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private Map<String, Object> metadata;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 20)
    private DataOrigin dataOrigin;

    @PrePersist
    void prePersist() {
        if (dependencyId == null) {
            dependencyId = UUID.randomUUID();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    @AssertTrue(message = "a flight cannot depend on itself")
    boolean isNotSelfDependency() {
        if (sourceFlight == null || targetFlight == null) {
            return true;
        }
        if (sourceFlight == targetFlight) {
            return false;
        }
        return sourceFlight.getFlightId() == null || targetFlight.getFlightId() == null
                || !Objects.equals(sourceFlight.getFlightId(), targetFlight.getFlightId());
    }
}
