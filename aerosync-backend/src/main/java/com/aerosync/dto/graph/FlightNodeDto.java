package com.aerosync.dto.graph;

import com.aerosync.graph.NodeType;
import com.aerosync.graph.FlightProvenance;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flight-instance node. Instants are UTC; the {@code *_local} strings are the
 * same instants rendered in the origin (departure) or destination (arrival)
 * airport's time zone for display. {@code provenance} says which system the
 * record came from; {@code disruption} is the flight's open disruption, if any.
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
        DataOrigin dataOrigin,
        FlightProvenance provenance,
        FlightDisruptionDto disruption) implements GraphNodeDto {

    /** The same node with its open disruption attached (Stage 4). */
    public FlightNodeDto withDisruption(FlightDisruptionDto open) {
        return new FlightNodeDto(id, type, flightInstanceId, scheduleId, flightNumber, operatingAirline,
                operatingDate, originAirport, destinationAirport, scheduledDeparture, scheduledArrival,
                scheduledDepartureLocal, scheduledArrivalLocal, estimatedDeparture, estimatedArrival,
                actualDeparture, actualArrival, status, delayMinutes, aircraft, assignedGate, dataOrigin,
                provenance, open);
    }
}
