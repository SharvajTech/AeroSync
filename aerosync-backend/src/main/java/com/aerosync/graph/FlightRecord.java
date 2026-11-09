package com.aerosync.graph;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstanceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence-independent view of one flight instance joined with its
 * schedule, airports and aircraft. All instants are UTC.
 */
public record FlightRecord(
        UUID flightId,
        UUID scheduleId,
        String flightNumber,
        LocalDate serviceDate,
        String originIata,
        String destinationIata,
        Instant scheduledDeparture,
        Instant scheduledArrival,
        Instant estimatedDeparture,
        Instant estimatedArrival,
        Instant actualDeparture,
        Instant actualArrival,
        int delayMinutes,
        FlightInstanceStatus status,
        String operatingAirline,
        String scheduledAircraftType,
        UUID aircraftId,
        String aircraftRegistration,
        String aircraftType,
        String assignedGate,
        DataOrigin dataOrigin) {

    public DependencyRules.FlightLeg toLeg() {
        return new DependencyRules.FlightLeg(flightId, flightNumber, originIata, destinationIata,
                scheduledDeparture, scheduledArrival, aircraftId);
    }
}
