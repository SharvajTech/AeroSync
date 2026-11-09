package com.aerosync.service;

import com.aerosync.graph.AirportRecord;
import com.aerosync.graph.DependencyRecord;
import com.aerosync.graph.FlightRecord;
import com.aerosync.model.Aircraft;
import com.aerosync.model.Airport;
import com.aerosync.model.FlightDependency;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.Gate;

/**
 * Maps JPA entities to the persistence-independent records consumed by the
 * graph core. Callers must have fetched the associations they need (the
 * repository graph queries do this with fetch joins).
 */
final class OperationalRecordMapper {

    private OperationalRecordMapper() {
    }

    static AirportRecord toRecord(Airport airport) {
        return new AirportRecord(
                airport.getAirportId(),
                airport.getIataCode(),
                airport.getAirportName(),
                airport.getCity(),
                airport.getTimezone(),
                airport.getLatitude(),
                airport.getLongitude(),
                airport.getStatus());
    }

    static FlightRecord toRecord(FlightInstance instance) {
        FlightSchedule schedule = instance.getSchedule();
        Aircraft aircraft = instance.getAircraft();
        Gate gate = instance.getAssignedGate();
        return new FlightRecord(
                instance.getFlightId(),
                schedule.getScheduleId(),
                schedule.getFlightNumber(),
                schedule.getServiceDate(),
                schedule.getOrigin().getIataCode(),
                schedule.getDestination().getIataCode(),
                schedule.getScheduledDeparture(),
                schedule.getScheduledArrival(),
                instance.getEstimatedDeparture(),
                instance.getEstimatedArrival(),
                instance.getActualDeparture(),
                instance.getActualArrival(),
                instance.getDelayMinutes() == null ? 0 : instance.getDelayMinutes(),
                instance.getStatus(),
                schedule.getOperatingAirline(),
                schedule.getAircraftType(),
                aircraft == null ? null : aircraft.getAircraftId(),
                aircraft == null ? null : aircraft.getRegistration(),
                aircraft == null ? null : aircraft.getAircraftType(),
                gate == null ? null : gate.getGateNumber(),
                schedule.getDataOrigin());
    }

    static DependencyRecord toRecord(FlightDependency dependency) {
        FlightInstance source = dependency.getSourceFlight();
        FlightInstance target = dependency.getTargetFlight();
        return new DependencyRecord(
                dependency.getDependencyId(),
                source.getFlightId(),
                target.getFlightId(),
                dependency.getDependencyType(),
                dependency.getImpactWeight(),
                dependency.getMinimumConnectionMinutes(),
                Boolean.TRUE.equals(dependency.getActive()),
                dependency.getMetadata(),
                source.getSchedule().getScheduledDeparture(),
                target.getSchedule().getScheduledDeparture(),
                dependency.getDataOrigin());
    }
}
