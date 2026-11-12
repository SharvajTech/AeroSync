package com.aerosync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Weather at an airport (table {@code weather_reports}).
 *
 * <p>{@link Kind#OBSERVATION} rows are measured conditions at {@code validAt};
 * {@link Kind#FORECAST} rows are predictions for {@code validAt}. The two are
 * never mixed: disruption detection and delay prediction read observations
 * only.</p>
 */
@Entity
@Table(name = "weather_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_weather_reports_report",
                columnNames = {"airport_id", "report_kind", "valid_at", "data_source"}),
        indexes = @Index(name = "idx_weather_reports_latest", columnList = "airport_id, report_kind, valid_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WeatherReport {

    public enum Kind {
        OBSERVATION,
        FORECAST
    }

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "airport_id", nullable = false, updatable = false)
    private UUID airportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_kind", nullable = false, length = 12)
    private Kind kind;

    /** Observation time, or the time a forecast applies to. */
    @Column(name = "valid_at", nullable = false)
    private Instant validAt;

    /** When AeroSync fetched the report. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** AeroSync's 0-3 operational score (0 clear, 3 severe). */
    @Column(name = "severity_score", nullable = false)
    private int severityScore;

    /** The source's condition code (OpenWeatherMap weather id). */
    @Column(name = "condition_code")
    private Integer conditionCode;

    @Column(name = "description", length = 120)
    private String description;

    @Column(name = "wind_speed_ms")
    private Double windSpeedMs;

    @Column(name = "visibility_m")
    private Integer visibilityM;

    @Column(name = "temperature_c")
    private Double temperatureC;

    /** Rain plus snow in millimetres: last hour (observation) or the forecast step. */
    @Column(name = "precipitation_mm")
    private Double precipitationMm;

    /** 0..1, forecasts only. */
    @Column(name = "precipitation_probability")
    private Double precipitationProbability;

    @Column(name = "data_source", nullable = false, length = 40)
    private String dataSource;

    @PrePersist
    void prePersist() {
        if (reportId == null) {
            reportId = UUID.randomUUID();
        }
    }
}
