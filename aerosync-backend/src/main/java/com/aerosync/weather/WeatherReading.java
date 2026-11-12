package com.aerosync.weather;

import com.aerosync.model.WeatherReport;

import java.time.Instant;

/**
 * One weather report as parsed from the source, before it is stored.
 *
 * @param kind    OBSERVATION (measured at {@code validAt}) or FORECAST (predicted for {@code validAt})
 * @param validAt the source's own timestamp ({@code dt}), not the fetch time
 */
public record WeatherReading(
        WeatherReport.Kind kind,
        Instant validAt,
        int severityScore,
        Integer conditionCode,
        String description,
        Double windSpeedMs,
        Integer visibilityM,
        Double temperatureC,
        Double precipitationMm,
        Double precipitationProbability) {
}
