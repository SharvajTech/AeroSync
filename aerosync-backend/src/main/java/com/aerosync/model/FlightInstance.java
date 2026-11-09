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
import jakarta.persistence.Version;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One operating instance of a scheduled leg (table {@code flight_instances}).
 * {@code flightId} is the unique operating-instance id used in graph node ids
 * ({@code flight:<flightId>}). There is at most one instance per schedule.
 */
@Entity
@Table(name = "flight_instances",
        uniqueConstraints = @UniqueConstraint(name = "uk_flight_instances_schedule", columnNames = "schedule_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightInstance {

    @Id
    @Column(name = "flight_id", nullable = false, updatable = false)
    private UUID flightId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_flight_instances_schedule"))
    private FlightSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aircraft_id",
            foreignKey = @ForeignKey(name = "fk_flight_instances_aircraft"))
    private Aircraft aircraft;

    @Column(name = "estimated_departure")
    private Instant estimatedDeparture;

    @Column(name = "actual_departure")
    private Instant actualDeparture;

    @Column(name = "estimated_arrival")
    private Instant estimatedArrival;

    @Column(name = "actual_arrival")
    private Instant actualArrival;

    @NotNull
    @Min(0)
    @Column(name = "delay_minutes", nullable = false)
    private Integer delayMinutes;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FlightInstanceStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_gate_id",
            foreignKey = @ForeignKey(name = "fk_flight_instances_gate"))
    private Gate assignedGate;

    /** Optimistic lock: concurrent operational updates to one flight cannot silently overwrite each other. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        if (flightId == null) {
            flightId = UUID.randomUUID();
        }
        if (delayMinutes == null) {
            delayMinutes = 0;
        }
        if (status == null) {
            status = FlightInstanceStatus.SCHEDULED;
        }
    }

    @AssertTrue(message = "estimated arrival must be after estimated departure")
    boolean isEstimatedTimesOrdered() {
        return estimatedDeparture == null || estimatedArrival == null
                || estimatedArrival.isAfter(estimatedDeparture);
    }

    @AssertTrue(message = "actual arrival must be after actual departure")
    boolean isActualTimesOrdered() {
        return actualDeparture == null || actualArrival == null
                || actualArrival.isAfter(actualDeparture);
    }
}
