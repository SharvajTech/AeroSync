package com.aerosync.dto.operations;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Departures and arrivals of one airport for one local calendar day
 * ([windowStart, windowEnd) in UTC, computed from the airport's time zone).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirportOperationsResponse(
        AirportDto airport,
        LocalDate serviceDate,
        String timezone,
        Instant windowStart,
        Instant windowEnd,
        boolean syntheticData,
        String dataNotice,
        AirportOperationsSummaryDto summary,
        List<FlightMovementDto> departures,
        List<FlightMovementDto> arrivals,
        List<GateDto> gates) {
}
