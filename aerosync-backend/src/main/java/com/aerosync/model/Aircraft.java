package com.aerosync.model;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * An individual aircraft (tail). {@code currentAirport} is the last known
 * position; for synthetic data it is the position at the start of the
 * generated window, not a live position.
 */
@Entity
@Table(name = "aircraft",
        uniqueConstraints = @UniqueConstraint(name = "uk_aircraft_registration", columnNames = "registration"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Aircraft {

    @Id
    @Column(name = "aircraft_id", nullable = false, updatable = false)
    private UUID aircraftId;

    @NotBlank
    @Column(name = "registration", nullable = false, length = 10)
    private String registration;

    @NotBlank
    @Column(name = "aircraft_type", nullable = false, length = 10)
    private String aircraftType;

    @NotNull
    @Pattern(regexp = NetworkIdentifiers.AIRLINE_DESIGNATOR_REGEX, message = "must be an IATA airline designator")
    @Column(name = "operating_airline", nullable = false, length = 3)
    private String operatingAirline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_airport_id",
            foreignKey = @ForeignKey(name = "fk_aircraft_current_airport"))
    private Airport currentAirport;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AircraftStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 20)
    private DataOrigin dataOrigin;

    @PrePersist
    void prePersist() {
        if (aircraftId == null) {
            aircraftId = UUID.randomUUID();
        }
        if (status == null) {
            status = AircraftStatus.ACTIVE;
        }
    }
}
