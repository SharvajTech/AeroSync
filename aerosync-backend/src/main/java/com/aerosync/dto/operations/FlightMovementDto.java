package com.aerosync.dto.operations;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A departure or arrival as seen from one airport. {@code scheduledTime} is
 * the scheduled departure (departures) or arrival (arrivals) in UTC;
 * {@code scheduledTimeLocal} is the same instant in the airport's time zone.
 */
public record FlightMovementDto(
        String nodeId,
        UUID flightInstanceId,
        String flightNumber,
        String operatingAirline,
        LocalDate operatingDate,
        String counterpartAirport,
        Instant scheduledTime,
        String scheduledTimeLocal,
        Instant estimatedTime,
        Instant actualTime,
        FlightInstanceStatus status,
        int delayMinutes,
        String aircraftRegistration,
        String aircraftType,
        String assignedGate,
        DataOrigin dataOrigin) {
}
