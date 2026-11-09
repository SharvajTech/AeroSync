package com.aerosync.dto.operations;

import java.util.Map;

public record AirportOperationsSummaryDto(
        int departureCount,
        int arrivalCount,
        int delayedDepartureCount,
        int cancelledDepartureCount,
        int cancelledArrivalCount,
        double averageDepartureDelayMinutes,
        Map<String, Integer> departuresByStatus,
        Map<String, Integer> departuresByAirline) {
}
