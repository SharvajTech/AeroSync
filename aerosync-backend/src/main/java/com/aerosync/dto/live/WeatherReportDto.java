package com.aerosync.dto.live;

import com.aerosync.model.WeatherReport;
import com.aerosync.weather.WeatherScoring;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One weather report. {@code kind} tells an observation (measured at
 * {@code validAt}) from a forecast (predicted for {@code validAt}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherReportDto(
        WeatherReport.Kind kind,
        Instant validAt,
        Instant fetchedAt,
        int severityScore,
        String severityLabel,
        Integer conditionCode,
        String description,
        Double windSpeedMs,
        Integer visibilityM,
        Double temperatureC,
        Double precipitationMm,
        Double precipitationProbability,
        String dataSource) {

    public static WeatherReportDto of(WeatherReport report) {
        return new WeatherReportDto(report.getKind(), report.getValidAt(), report.getFetchedAt(),
                report.getSeverityScore(), WeatherScoring.label(report.getSeverityScore()),
                report.getConditionCode(), report.getDescription(), report.getWindSpeedMs(),
                report.getVisibilityM(), report.getTemperatureC(), report.getPrecipitationMm(),
                report.getPrecipitationProbability(), report.getDataSource());
    }
}
