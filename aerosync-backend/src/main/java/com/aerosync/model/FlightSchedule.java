package com.aerosync.model;

import com.aerosync.datasource.DataSources;
import com.aerosync.graph.NetworkIdentifiers;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The planned leg: one flight number on one service date from one departure
 * station (table {@code flight_schedules}).
 *
 * <p>Flight numbers repeat every day, so a flight number is never an identifier
 * on its own. The leg key is (flight_number, service_date, origin_airport_id),
 * which also allows multi-leg flights that keep one number across stations.
 * All timestamps are UTC instants; {@code serviceDate} is the local date of
 * departure at the origin airport.</p>
 */
@Entity
@Table(name = "flight_schedules",
        uniqueConstraints = @UniqueConstraint(name = "uk_flight_schedules_leg",
                columnNames = {"flight_number", "service_date", "origin_airport_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightSchedule {

    @Id
    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @NotNull
    @Pattern(regexp = NetworkIdentifiers.FLIGHT_NUMBER_REGEX, message = "must look like AI101 or 6E2043")
    @Column(name = "flight_number", nullable = false, length = 8)
    private String flightNumber;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_airport_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_schedules_origin"))
    private Airport origin;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_airport_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_schedules_destination"))
    private Airport destination;

    @NotNull
    @Column(name = "scheduled_departure", nullable = false)
    private Instant scheduledDeparture;

    @NotNull
    @Column(name = "scheduled_arrival", nullable = false)
    private Instant scheduledArrival;

    @NotNull
    @Pattern(regexp = NetworkIdentifiers.AIRLINE_DESIGNATOR_REGEX, message = "must be an IATA airline designator")
    @Column(name = "operating_airline", nullable = false, length = 3)
    private String operatingAirline;

    /** Planned aircraft type; null when the source does not report it (OpenSky). */
    @Column(name = "aircraft_type", length = 10)
    private String aircraftType;

    @NotNull
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 20)
    private DataOrigin dataOrigin;

    /**
     * Which system wrote the row (see {@link com.aerosync.datasource.DataSources}).
     * Defaults from {@link #dataOrigin} when not set.
     */
    @Column(name = "data_source", nullable = false, length = 40)
    private String dataSource;

    /**
     * PLANNED: {@code scheduledDeparture/Arrival} are a timetable. OBSERVED:
     * no timetable is known and they hold the observed departure and arrival.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_basis", nullable = false, length = 20)
    private ScheduleBasis scheduleBasis;

    /** Identifier of the record in the external source; null for local records. */
    @Column(name = "source_record_id", length = 64)
    private String sourceRecordId;

    /** Callsign reported by the external source, if any. */
    @Column(name = "callsign", length = 8)
    private String callsign;

    /** Transponder address (lower-case hex) reported by the external source, if any. */
    @Column(name = "aircraft_icao24", length = 6)
    private String aircraftIcao24;

    /** When ingestion first wrote the row; null for rows not written by ingestion. */
    @Column(name = "ingested_at")
    private Instant ingestedAt;

    /** When ingestion last confirmed or changed the row. */
    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @PrePersist
    void prePersist() {
        if (scheduleId == null) {
            scheduleId = UUID.randomUUID();
        }
        if (dataSource == null) {
            dataSource = DataSources.defaultFor(dataOrigin);
        }
        if (scheduleBasis == null) {
            scheduleBasis = ScheduleBasis.PLANNED;
        }
    }

    @AssertTrue(message = "scheduled arrival must be after scheduled departure")
    boolean isArrivalAfterDeparture() {
        return scheduledDeparture == null || scheduledArrival == null
                || scheduledArrival.isAfter(scheduledDeparture);
    }

    @AssertTrue(message = "origin and destination airports must differ")
    boolean isDistinctAirports() {
        if (origin == null || destination == null) {
            return true;
        }
        if (origin == destination) {
            return false;
        }
        return origin.getAirportId() == null || destination.getAirportId() == null
                || !Objects.equals(origin.getAirportId(), destination.getAirportId());
    }
}
