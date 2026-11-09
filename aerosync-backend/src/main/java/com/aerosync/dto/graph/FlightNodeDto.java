package com.aerosync.dto.graph;

import com.aerosync.graph.NodeType;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flight-instance node. Instants are UTC; the {@code *_local} strings are the
 * same instants rendered in the origin (departure) or destination (arrival)
 * airport's time zone for display.
 */
public record FlightNodeDto(
        String id,
        NodeType type,
        UUID flightInstanceId,
        UUID scheduleId,
        String flightNumber,
        String operatingAirline,
        LocalDate operatingDate,
        String originAirport,
        String destinationAirport,
        Instant scheduledDeparture,
        Instant scheduledArrival,
        String scheduledDepartureLocal,
        String scheduledArrivalLocal,
        Instant estimatedDeparture,
        Instant estimatedArrival,
        Instant actualDeparture,
        Instant actualArrival,
        FlightInstanceStatus status,
        int delayMinutes,
        AircraftInfoDto aircraft,
        String assignedGate,
        DataOrigin dataOrigin) implements GraphNodeDto {
}
