package com.aerosync.model;

import com.aerosync.graph.NetworkIdentifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * Airport node of the operational network (table {@code airports}).
 * Schema is owned by Flyway migration V1; reference rows by V2.
 */
@Entity
@Table(name = "airports",
        uniqueConstraints = @UniqueConstraint(name = "uk_airports_iata_code", columnNames = "iata_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Airport {

    @Id
    @Column(name = "airport_id", nullable = false, updatable = false)
    private UUID airportId;

    @NotNull
    @Pattern(regexp = NetworkIdentifiers.IATA_CODE_REGEX, message = "must be a three-letter IATA code")
    @Column(name = "iata_code", nullable = false, length = 3)
    private String iataCode;

    /** ICAO location indicator (e.g. VABB), the airport key of OpenSky; null when unknown. */
    @Pattern(regexp = "^[A-Z]{4}$", message = "must be a four-letter ICAO code")
    @Column(name = "icao_code", length = 4)
    private String icaoCode;

    @NotBlank
    @Column(name = "airport_name", nullable = false, length = 120)
    private String airportName;

    @NotBlank
    @Column(name = "city", nullable = false, length = 80)
    private String city;

    /** IANA zone id (e.g. Asia/Kolkata). Timestamps are stored in UTC; this is for local display only. */
    @NotBlank
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AirportStatus status;

    @PrePersist
    void prePersist() {
        if (airportId == null) {
            airportId = UUID.randomUUID();
        }
        if (status == null) {
            status = AirportStatus.OPERATIONAL;
        }
        normalize();
    }

    @PreUpdate
    void preUpdate() {
        normalize();
    }

    private void normalize() {
        if (iataCode != null) {
            iataCode = iataCode.trim().toUpperCase(Locale.ROOT);
        }
        if (icaoCode != null) {
            icaoCode = icaoCode.isBlank() ? null : icaoCode.trim().toUpperCase(Locale.ROOT);
        }
    }
}
