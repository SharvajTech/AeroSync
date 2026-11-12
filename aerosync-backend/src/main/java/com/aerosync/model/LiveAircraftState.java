package com.aerosync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Latest externally observed state of one aircraft (table
 * {@code live_aircraft_states}, one row per transponder address).
 *
 * <p>Rows are written only by ingestion from an external source and are never
 * generated. Position and motion are as reported; {@code flightNumber},
 * {@code operatingAirline} and the nearest airport are derived by AeroSync
 * and are null when they cannot be derived.</p>
 */
@Entity
@Table(name = "live_aircraft_states", indexes = {
        @Index(name = "idx_live_aircraft_states_airport", columnList = "nearest_airport_id, last_contact"),
        @Index(name = "idx_live_aircraft_states_last_contact", columnList = "last_contact")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveAircraftState {

    /** ICAO 24-bit transponder address, lower-case hex. */
    @Id
    @Column(name = "icao24", nullable = false, updatable = false, length = 6)
    private String icao24;

    @Column(name = "callsign", length = 8)
    private String callsign;

    @Column(name = "flight_number", length = 8)
    private String flightNumber;

    @Column(name = "operating_airline", length = 3)
    private String operatingAirline;

    @Column(name = "origin_country", length = 80)
    private String originCountry;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "baro_altitude_m")
    private Double baroAltitudeM;

    @Column(name = "geo_altitude_m")
    private Double geoAltitudeM;

    @Column(name = "on_ground", nullable = false)
    private boolean onGround;

    @Column(name = "velocity_ms")
    private Double velocityMs;

    @Column(name = "true_track_deg")
    private Double trueTrackDeg;

    @Column(name = "vertical_rate_ms")
    private Double verticalRateMs;

    @Column(name = "squawk", length = 4)
    private String squawk;

    /** 0 ADS-B, 1 ASTERIX, 2 MLAT, 3 FLARM (OpenSky). */
    @Column(name = "position_source")
    private Integer positionSource;

    @Column(name = "time_position")
    private Instant timePosition;

    /** Time of the last message received from the aircraft. */
    @Column(name = "last_contact", nullable = false)
    private Instant lastContact;

    /** Network airport within the configured radius of the position; null when en route. */
    @Column(name = "nearest_airport_id")
    private UUID nearestAirportId;

    @Column(name = "nearest_airport_distance_km")
    private Double nearestAirportDistanceKm;

    @Column(name = "data_source", nullable = false, length = 40)
    private String dataSource;

    /** Time the source snapshot is valid for. */
    @Column(name = "snapshot_time", nullable = false)
    private Instant snapshotTime;

    /** When AeroSync stored this state. */
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;
}
