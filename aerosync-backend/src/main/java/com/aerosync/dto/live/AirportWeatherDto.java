package com.aerosync.dto.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Weather at one airport.
 *
 * @param status      LIVE: a current observation exists. STALE: the last
 *                    observation is older than the accepted age and is shown
 *                    for reference only. UNAVAILABLE: there is none (no API
 *                    key, or the source could not be read).
 * @param observation the latest observation; absent when UNAVAILABLE
 * @param forecast    upcoming forecast steps, earliest first; predictions, not observations
 * @param note        why the status is not LIVE
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirportWeatherDto(
        String airport,
        String status,
        boolean sourceConfigured,
        WeatherReportDto observation,
        Long observationAgeMinutes,
        List<WeatherReportDto> forecast,
        String note) {

    public static final String LIVE = "LIVE";
    public static final String STALE = "STALE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
}
