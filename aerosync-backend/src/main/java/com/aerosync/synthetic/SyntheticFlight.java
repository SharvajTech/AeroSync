package com.aerosync.synthetic;

import com.aerosync.graph.DependencyRules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One synthetic scheduled leg and its operating instance (planned times only, no actuals). */
public record SyntheticFlight(
        UUID flightInstanceId,
        UUID scheduleId,
        String flightNumber,
        String operatingAirline,
        String originIata,
        String destinationIata,
        LocalDate serviceDate,
        Instant scheduledDeparture,
        Instant scheduledArrival,
        String aircraftType,
        UUID aircraftId,
        String aircraftRegistration) {

    public DependencyRules.FlightLeg toLeg() {
        return new DependencyRules.FlightLeg(flightInstanceId, flightNumber, originIata, destinationIata,
                scheduledDeparture, scheduledArrival, aircraftId);
    }
}
