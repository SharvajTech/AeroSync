package com.aerosync.service;

import com.aerosync.exception.DuplicateFlightInstanceException;
import com.aerosync.exception.InvalidReferenceException;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.graph.DependencyRules;
import com.aerosync.graph.InvalidDependencyException;
import com.aerosync.graph.NetworkIdentifiers;
import com.aerosync.model.Aircraft;
import com.aerosync.model.Airport;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightDependency;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.repository.AircraftRepository;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.FlightDependencyRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Validated write path for the operational network (used by importers and
 * tests; no HTTP endpoint in Stage 1). Every write runs in one transaction:
 * references are checked first, then the database constraints back the checks
 * up (unique leg key, unique instance per schedule, FKs, no self-loops).
 */
@Service
@RequiredArgsConstructor
public class FlightNetworkService {

    private final AirportRepository airportRepository;
    private final AircraftRepository aircraftRepository;
    private final FlightScheduleRepository flightScheduleRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightDependencyRepository flightDependencyRepository;

    /** A planned leg to create. {@code aircraftRegistration} is optional. */
    public record NewFlight(
            String flightNumber,
            String operatingAirline,
            String originIata,
            String destinationIata,
            LocalDate serviceDate,
            Instant scheduledDeparture,
            Instant scheduledArrival,
            String aircraftType,
            String aircraftRegistration,
            DataOrigin dataOrigin) {
    }

    public record NewDependency(
            UUID sourceFlightId,
            UUID targetFlightId,
            DependencyType type,
            double impactWeight,
            Integer minimumConnectionMinutes,
            Map<String, Object> metadata,
            DataOrigin dataOrigin) {
    }

    /**
     * Creates the schedule and its single operating instance.
     *
     * @throws InvalidReferenceException       unknown airport or aircraft, or origin equals destination
     * @throws InvalidRequestParameterException malformed flight number or inconsistent times/date
     * @throws DuplicateFlightInstanceException the leg already exists for that service date
     */
    @Transactional
    public FlightInstance createFlight(NewFlight request) {
        String flightNumber = request.flightNumber() == null ? null
                : request.flightNumber().trim().toUpperCase(Locale.ROOT);
        if (!NetworkIdentifiers.isValidFlightNumber(flightNumber)) {
            throw new InvalidRequestParameterException("Invalid flight number '" + request.flightNumber()
                    + "': expected airline designator + 1-4 digits, e.g. AI101");
        }
        if (request.serviceDate() == null || request.scheduledDeparture() == null
                || request.scheduledArrival() == null) {
            throw new InvalidRequestParameterException("service date, scheduled departure and arrival are required");
        }
        Airport origin = requireAirportReference(request.originIata(), "origin");
        Airport destination = requireAirportReference(request.destinationIata(), "destination");
        if (origin.getAirportId().equals(destination.getAirportId())) {
            throw new InvalidReferenceException("Origin and destination must be different airports ("
                    + origin.getIataCode() + ")");
        }
        if (!request.scheduledArrival().isAfter(request.scheduledDeparture())) {
            throw new InvalidRequestParameterException("Scheduled arrival must be after scheduled departure");
        }
        LocalDate localDepartureDate = request.scheduledDeparture()
                .atZone(ZoneId.of(origin.getTimezone())).toLocalDate();
        if (!localDepartureDate.equals(request.serviceDate())) {
            throw new InvalidRequestParameterException("service_date " + request.serviceDate()
                    + " must be the local departure date at " + origin.getIataCode() + " (" + localDepartureDate + ")");
        }
        if (flightScheduleRepository.existsByFlightNumberAndServiceDateAndOrigin_AirportId(
                flightNumber, request.serviceDate(), origin.getAirportId())) {
            throw new DuplicateFlightInstanceException("Flight " + flightNumber + " already operates from "
                    + origin.getIataCode() + " on " + request.serviceDate());
        }
        Aircraft aircraft = null;
        if (request.aircraftRegistration() != null && !request.aircraftRegistration().isBlank()) {
            aircraft = aircraftRepository.findByRegistration(request.aircraftRegistration().trim())
                    .orElseThrow(() -> new InvalidReferenceException(
                            "Unknown aircraft registration " + request.aircraftRegistration()));
        }
        String airline = request.operatingAirline() != null ? request.operatingAirline()
                : flightNumber.substring(0, 2);

        FlightSchedule schedule = flightScheduleRepository.save(FlightSchedule.builder()
                .flightNumber(flightNumber)
                .origin(origin)
                .destination(destination)
                .scheduledDeparture(request.scheduledDeparture())
                .scheduledArrival(request.scheduledArrival())
                .operatingAirline(airline)
                .aircraftType(request.aircraftType() != null ? request.aircraftType()
                        : aircraft != null ? aircraft.getAircraftType() : null)
                .serviceDate(request.serviceDate())
                .dataOrigin(request.dataOrigin() != null ? request.dataOrigin() : DataOrigin.MANUAL)
                .build());
        return flightInstanceRepository.saveAndFlush(FlightInstance.builder()
                .schedule(schedule)
                .aircraft(aircraft)
                .status(FlightInstanceStatus.SCHEDULED)
                .delayMinutes(0)
                .build());
    }

    /**
     * Creates a dependency after checking both flights exist and the
     * {@link DependencyRules} hold.
     *
     * @throws InvalidReferenceException  unknown source or target flight instance
     * @throws InvalidDependencyException rule violation or duplicate edge
     */
    @Transactional
    public FlightDependency createDependency(NewDependency request) {
        if (request.type() == null) {
            throw new InvalidDependencyException(List.of("dependency type is required"));
        }
        FlightInstance source = requireFlightReference(request.sourceFlightId(), "source");
        FlightInstance target = requireFlightReference(request.targetFlightId(), "target");
        DependencyRules.requireValid(
                OperationalRecordMapper.toRecord(source).toLeg(),
                OperationalRecordMapper.toRecord(target).toLeg(),
                request.type(), request.impactWeight(), request.minimumConnectionMinutes());
        if (flightDependencyRepository.existsBySourceFlight_FlightIdAndTargetFlight_FlightIdAndDependencyType(
                source.getFlightId(), target.getFlightId(), request.type())) {
            throw new InvalidDependencyException(List.of("a " + request.type() + " dependency from "
                    + source.getFlightId() + " to " + target.getFlightId() + " already exists"));
        }
        return flightDependencyRepository.saveAndFlush(FlightDependency.builder()
                .sourceFlight(source)
                .targetFlight(target)
                .dependencyType(request.type())
                .impactWeight(request.impactWeight())
                .minimumConnectionMinutes(request.minimumConnectionMinutes())
                .metadata(request.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metadata()))
                .active(Boolean.TRUE)
                .dataOrigin(request.dataOrigin() != null ? request.dataOrigin() : DataOrigin.MANUAL)
                .build());
    }

    private Airport requireAirportReference(String iataCode, String role) {
        String iata;
        try {
            iata = NetworkIdentifiers.normalizeIata(iataCode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidReferenceException("Invalid " + role + " airport: " + ex.getMessage());
        }
        return airportRepository.findByIataCode(iata)
                .orElseThrow(() -> new InvalidReferenceException("Unknown " + role + " airport " + iata));
    }

    private FlightInstance requireFlightReference(UUID flightId, String role) {
        if (flightId == null) {
            throw new InvalidReferenceException("The " + role + " flight instance id is required");
        }
        return flightInstanceRepository.findGraphFlightById(flightId)
                .orElseThrow(() -> new InvalidReferenceException("Unknown " + role + " flight instance " + flightId));
    }
}
