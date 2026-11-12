package com.aerosync.service;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.model.Aircraft;
import com.aerosync.model.AircraftStatus;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
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
import com.aerosync.synthetic.ReferenceAirports;
import com.aerosync.synthetic.ReferenceAirports.ReferenceAirport;
import com.aerosync.synthetic.SyntheticAircraft;
import com.aerosync.synthetic.SyntheticDependency;
import com.aerosync.synthetic.SyntheticFlight;
import com.aerosync.synthetic.SyntheticNetwork;
import com.aerosync.synthetic.SyntheticNetworkGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the SYNTHETIC operational network (aircraft, schedules, instances,
 * dependencies) on startup when the operational tables are empty.
 *
 * <p>Idempotent: nothing is written if any aircraft, schedule, instance or
 * dependency already exists, so restarts never duplicate or overwrite data.
 * All rows are written in one transaction (all or nothing) and are marked
 * {@code data_origin = SYNTHETIC}. Disable with
 * {@code AEROSYNC_SYNTHETIC_NETWORK_ENABLED=false}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyntheticNetworkSeeder implements ApplicationRunner {

    private static final int FLUSH_BATCH_SIZE = 500;

    private final AirportRepository airportRepository;
    private final AircraftRepository aircraftRepository;
    private final FlightScheduleRepository flightScheduleRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightDependencyRepository flightDependencyRepository;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${aerosync.synthetic-network.enabled:true}")
    private boolean enabled;

    @Value("${aerosync.synthetic-network.days:7}")
    private int days;

    /** ISO date (yyyy-MM-dd) of the first service date; blank = today in the operations time zone. */
    @Value("${aerosync.synthetic-network.start-date:}")
    private String startDate;

    @Value("${aerosync.synthetic-network.random-seed:20261109}")
    private long randomSeed;

    @Value("${aerosync.operations.timezone:Asia/Kolkata}")
    private String operationsTimezone;

    /** Stage 4: SYNTHETIC, LIVE or HYBRID. Nothing synthetic is seeded in LIVE mode. */
    @Value("${aerosync.data.mode:SYNTHETIC}")
    private String dataMode;

    /** Outcome of a seeding attempt. */
    public record SeedResult(
            boolean seeded,
            String message,
            LocalDate startDate,
            int days,
            int airportsInserted,
            int aircraft,
            int flights,
            Map<DependencyType, Integer> dependencies) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Synthetic operational network seeding is disabled");
            return;
        }
        if (!DataSourceMode.parse(dataMode).generatesSyntheticData()) {
            log.info("event=synthetic_network_skipped data_mode={}", dataMode.trim().toUpperCase());
            return;
        }
        LocalDate start = resolveStartDate();
        try {
            SeedResult result = seed(start, days, randomSeed);
            if (result.seeded()) {
                log.info("Seeded SYNTHETIC operational network: {} service dates from {}, {} flights, "
                                + "{} aircraft, dependencies {} ({} reference airports inserted)",
                        result.days(), result.startDate(), result.flights(), result.aircraft(),
                        result.dependencies(), result.airportsInserted());
            } else {
                log.info("Synthetic operational network not seeded: {}", result.message());
            }
        } catch (DataIntegrityViolationException ex) {
            // Another backend instance may have seeded concurrently; anything else is a real error.
            if (flightInstanceRepository.count() > 0) {
                log.warn("Synthetic network was seeded concurrently by another instance: {}",
                        ex.getMostSpecificCause().getMessage());
                return;
            }
            throw ex;
        }
    }

    /** Seeds the network in one transaction unless operational data already exists. */
    public SeedResult seed(LocalDate firstServiceDate, int numberOfDays, long seed) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> doSeed(firstServiceDate, numberOfDays, seed));
    }

    private SeedResult doSeed(LocalDate firstServiceDate, int numberOfDays, long seed) {
        long existing = aircraftRepository.count() + flightScheduleRepository.count()
                + flightInstanceRepository.count() + flightDependencyRepository.count();
        if (existing > 0) {
            return new SeedResult(false, "operational tables already contain " + existing + " rows",
                    firstServiceDate, numberOfDays, 0, 0, 0, Map.of());
        }

        int airportsInserted = 0;
        Map<String, UUID> airportIds = new HashMap<>();
        for (ReferenceAirport reference : ReferenceAirports.ALL) {
            Airport airport = airportRepository.findByIataCode(reference.iataCode()).orElse(null);
            if (airport == null) {
                airport = Airport.builder()
                        .airportId(reference.airportId())
                        .iataCode(reference.iataCode())
                        .icaoCode(ReferenceAirports.icaoCode(reference.iataCode()))
                        .airportName(reference.airportName())
                        .city(reference.city())
                        .timezone(reference.timezone())
                        .latitude(reference.latitude())
                        .longitude(reference.longitude())
                        .status(AirportStatus.OPERATIONAL)
                        .build();
                entityManager.persist(airport);
                airportsInserted++;
            }
            airportIds.put(reference.iataCode(), airport.getAirportId());
        }
        entityManager.flush();

        SyntheticNetwork network = SyntheticNetworkGenerator.generate(firstServiceDate, numberOfDays, seed);

        int pending = 0;
        for (SyntheticAircraft aircraft : network.aircraft()) {
            entityManager.persist(Aircraft.builder()
                    .aircraftId(aircraft.aircraftId())
                    .registration(aircraft.registration())
                    .aircraftType(aircraft.aircraftType())
                    .operatingAirline(aircraft.operatingAirline())
                    .currentAirport(airportReference(airportIds, aircraft.initialAirportIata()))
                    .status(AircraftStatus.ACTIVE)
                    .dataOrigin(DataOrigin.SYNTHETIC)
                    .build());
            pending = flushIfNeeded(pending + 1);
        }
        for (SyntheticFlight flight : network.flights()) {
            FlightSchedule schedule = FlightSchedule.builder()
                    .scheduleId(flight.scheduleId())
                    .flightNumber(flight.flightNumber())
                    .origin(airportReference(airportIds, flight.originIata()))
                    .destination(airportReference(airportIds, flight.destinationIata()))
                    .scheduledDeparture(flight.scheduledDeparture())
                    .scheduledArrival(flight.scheduledArrival())
                    .operatingAirline(flight.operatingAirline())
                    .aircraftType(flight.aircraftType())
                    .serviceDate(flight.serviceDate())
                    .dataOrigin(DataOrigin.SYNTHETIC)
                    .build();
            entityManager.persist(schedule);
            entityManager.persist(FlightInstance.builder()
                    .flightId(flight.flightInstanceId())
                    .schedule(schedule)
                    .aircraft(entityManager.getReference(Aircraft.class, flight.aircraftId()))
                    .status(FlightInstanceStatus.SCHEDULED)
                    .delayMinutes(0)
                    .build());
            pending = flushIfNeeded(pending + 2);
        }
        for (SyntheticDependency dependency : network.dependencies()) {
            entityManager.persist(FlightDependency.builder()
                    .dependencyId(dependency.dependencyId())
                    .sourceFlight(entityManager.getReference(FlightInstance.class, dependency.sourceFlightId()))
                    .targetFlight(entityManager.getReference(FlightInstance.class, dependency.targetFlightId()))
                    .dependencyType(dependency.type())
                    .impactWeight(dependency.impactWeight())
                    .minimumConnectionMinutes(dependency.minimumConnectionMinutes())
                    .metadata(new LinkedHashMap<>(dependency.metadata()))
                    .active(Boolean.TRUE)
                    .dataOrigin(DataOrigin.SYNTHETIC)
                    .build());
            pending = flushIfNeeded(pending + 1);
        }
        entityManager.flush();
        entityManager.clear();

        return new SeedResult(true, "seeded", firstServiceDate, numberOfDays, airportsInserted,
                network.aircraft().size(), network.flights().size(), network.dependencyCounts());
    }

    private Airport airportReference(Map<String, UUID> airportIds, String iata) {
        UUID id = airportIds.get(iata);
        if (id == null) {
            throw new IllegalStateException("Reference airport " + iata + " is missing");
        }
        return entityManager.getReference(Airport.class, id);
    }

    private int flushIfNeeded(int pending) {
        if (pending < FLUSH_BATCH_SIZE) {
            return pending;
        }
        entityManager.flush();
        entityManager.clear();
        return 0;
    }

    private LocalDate resolveStartDate() {
        if (startDate == null || startDate.isBlank()) {
            return LocalDate.now(ZoneId.of(operationsTimezone));
        }
        try {
            return LocalDate.parse(startDate.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException("aerosync.synthetic-network.start-date must be yyyy-MM-dd, was '"
                    + startDate + "'", ex);
        }
    }
}
