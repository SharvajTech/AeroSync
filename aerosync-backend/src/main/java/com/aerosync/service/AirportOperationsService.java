package com.aerosync.service;

import com.aerosync.dto.operations.AirportDto;
import com.aerosync.dto.operations.AirportOperationsResponse;
import com.aerosync.dto.operations.AirportOperationsSummaryDto;
import com.aerosync.dto.operations.FlightMovementDto;
import com.aerosync.dto.operations.GateDto;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.graph.FlightRecord;
import com.aerosync.graph.NetworkIdentifiers;
import com.aerosync.model.Airport;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.Gate;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.GateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tabular airport view (departures, arrivals, gates) backed by the operational tables. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AirportOperationsService {

    /** Departure delay at or above this many minutes counts as delayed (common on-time definition). */
    public static final int DELAY_THRESHOLD_MINUTES = 15;

    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AirportRepository airportRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final GateRepository gateRepository;

    public List<AirportDto> listAirports() {
        return airportRepository.findAllByOrderByIataCodeAsc().stream().map(AirportOperationsService::toDto).toList();
    }

    public AirportOperationsResponse getOperations(String iataCode, LocalDate serviceDate) {
        String iata;
        try {
            iata = NetworkIdentifiers.normalizeIata(iataCode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestParameterException(ex.getMessage());
        }
        Airport airport = airportRepository.findByIataCode(iata)
                .orElseThrow(() -> new ResourceNotFoundException("Airport " + iata + " not found"));
        ZoneId zone = ZoneId.of(airport.getTimezone());
        LocalDate day = serviceDate != null ? serviceDate : LocalDate.now(zone);
        Instant windowStart = day.atStartOfDay(zone).toInstant();
        Instant windowEnd = day.plusDays(1).atStartOfDay(zone).toInstant();

        List<FlightRecord> departures = new ArrayList<>();
        List<FlightRecord> arrivals = new ArrayList<>();
        for (FlightInstance instance : flightInstanceRepository.findAirportMovements(iata, windowStart, windowEnd)) {
            FlightRecord flight = OperationalRecordMapper.toRecord(instance);
            boolean departsInWindow = flight.originIata().equals(iata)
                    && !flight.scheduledDeparture().isBefore(windowStart) && flight.scheduledDeparture().isBefore(windowEnd);
            boolean arrivesInWindow = flight.destinationIata().equals(iata)
                    && !flight.scheduledArrival().isBefore(windowStart) && flight.scheduledArrival().isBefore(windowEnd);
            if (departsInWindow) {
                departures.add(flight);
            }
            if (arrivesInWindow) {
                arrivals.add(flight);
            }
        }
        departures.sort(Comparator.comparing(FlightRecord::scheduledDeparture).thenComparing(FlightRecord::flightNumber));
        arrivals.sort(Comparator.comparing(FlightRecord::scheduledArrival).thenComparing(FlightRecord::flightNumber));

        List<FlightMovementDto> departureRows = departures.stream().map(f -> departureRow(f, zone)).toList();
        List<FlightMovementDto> arrivalRows = arrivals.stream().map(f -> arrivalRow(f, zone)).toList();
        List<GateDto> gates = gateRepository.findByAirportIdOrderByTerminalAscGateNumberAsc(airport.getAirportId())
                .stream().map(AirportOperationsService::toDto).toList();

        boolean synthetic = departures.stream().anyMatch(f -> f.dataOrigin() == DataOrigin.SYNTHETIC)
                || arrivals.stream().anyMatch(f -> f.dataOrigin() == DataOrigin.SYNTHETIC);
        return new AirportOperationsResponse(
                toDto(airport),
                day,
                airport.getTimezone(),
                windowStart,
                windowEnd,
                synthetic,
                synthetic ? OperationalGraphService.SYNTHETIC_DATA_NOTICE : null,
                summarize(departures, arrivals),
                departureRows,
                arrivalRows,
                gates);
    }

    private static AirportOperationsSummaryDto summarize(List<FlightRecord> departures, List<FlightRecord> arrivals) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (FlightInstanceStatus status : FlightInstanceStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        Map<String, Integer> byAirline = new TreeMap<>();
        int delayed = 0;
        int cancelledDepartures = 0;
        long totalDelay = 0;
        for (FlightRecord flight : departures) {
            byStatus.merge(flight.status().name(), 1, Integer::sum);
            byAirline.merge(flight.operatingAirline(), 1, Integer::sum);
            if (flight.status() == FlightInstanceStatus.CANCELLED) {
                cancelledDepartures++;
                continue;
            }
            totalDelay += flight.delayMinutes();
            if (flight.delayMinutes() >= DELAY_THRESHOLD_MINUTES || flight.status() == FlightInstanceStatus.DELAYED) {
                delayed++;
            }
        }
        int cancelledArrivals = (int) arrivals.stream()
                .filter(f -> f.status() == FlightInstanceStatus.CANCELLED).count();
        int operated = departures.size() - cancelledDepartures;
        double averageDelay = operated == 0 ? 0.0 : Math.round(10.0 * totalDelay / operated) / 10.0;
        return new AirportOperationsSummaryDto(departures.size(), arrivals.size(), delayed, cancelledDepartures,
                cancelledArrivals, averageDelay, byStatus, byAirline);
    }

    private static FlightMovementDto departureRow(FlightRecord f, ZoneId zone) {
        return new FlightMovementDto(
                NetworkIdentifiers.flightNodeId(f.flightId()), f.flightId(), f.flightNumber(), f.operatingAirline(),
                f.serviceDate(), f.destinationIata(), f.scheduledDeparture(),
                LOCAL_FORMAT.format(f.scheduledDeparture().atZone(zone)),
                f.estimatedDeparture(), f.actualDeparture(), f.status(), f.delayMinutes(),
                f.aircraftRegistration(), f.aircraftType() != null ? f.aircraftType() : f.scheduledAircraftType(),
                f.assignedGate(), f.dataOrigin());
    }

    private static FlightMovementDto arrivalRow(FlightRecord f, ZoneId zone) {
        return new FlightMovementDto(
                NetworkIdentifiers.flightNodeId(f.flightId()), f.flightId(), f.flightNumber(), f.operatingAirline(),
                f.serviceDate(), f.originIata(), f.scheduledArrival(),
                LOCAL_FORMAT.format(f.scheduledArrival().atZone(zone)),
                f.estimatedArrival(), f.actualArrival(), f.status(), f.delayMinutes(),
                f.aircraftRegistration(), f.aircraftType() != null ? f.aircraftType() : f.scheduledAircraftType(),
                f.assignedGate(), f.dataOrigin());
    }

    static AirportDto toDto(Airport airport) {
        return new AirportDto(
                airport.getAirportId(),
                NetworkIdentifiers.airportNodeId(airport.getIataCode()),
                airport.getIataCode(),
                airport.getAirportName(),
                airport.getCity(),
                airport.getTimezone(),
                airport.getLatitude(),
                airport.getLongitude(),
                airport.getStatus());
    }

    private static GateDto toDto(Gate gate) {
        List<String> types = gate.getCompatibleTypes() == null || gate.getCompatibleTypes().isBlank()
                ? List.of()
                : Arrays.stream(gate.getCompatibleTypes().split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
        return new GateDto(gate.getId(), gate.getTerminal(), gate.getGateNumber(), types, gate.getStatus(),
                gate.getAvailable());
    }
}
