package com.aerosync.monitoring;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.datasource.DataSources;
import com.aerosync.model.Aircraft;
import com.aerosync.model.AircraftStatus;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionStatus;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.FlightDependency;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.WeatherReport;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightDependencyRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.WeatherReportRepository;
import com.aerosync.service.DisruptionService;
import com.aerosync.service.WeatherService;
import com.aerosync.testsupport.InMemoryRepository;
import com.aerosync.weather.OpenWeatherClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disruption monitor as a whole: it detects and stores, never acts; it
 * does not duplicate an open event; it closes what is no longer detected;
 * and it respects the data mode.
 */
class DisruptionMonitorServiceTest {

    private Instant now;
    private final AtomicLong ids = new AtomicLong();

    private InMemoryRepository<Flight, Long> flights;
    private InMemoryRepository<Disruption, Long> disruptions;
    private InMemoryRepository<Airport, UUID> airports;
    private InMemoryRepository<WeatherReport, UUID> reports;
    private InMemoryRepository<FlightInstance, UUID> instances;
    private InMemoryRepository<FlightDependency, UUID> dependencies;
    private Airport bom;
    private Airport del;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-10T06:00:00Z");
        flights = InMemoryRepository.of(Flight::getId, f -> f.setId(ids.incrementAndGet()));
        disruptions = InMemoryRepository.of(Disruption::getId, d -> d.setId(ids.incrementAndGet()));
        disruptions.query("findByStatusIn", (rows, args) -> rows.stream()
                .filter(d -> ((Collection<?>) args[0]).contains(d.getStatus())).toList());
        disruptions.query("findByDetectionSourceAndStatusIn", (rows, args) -> rows.stream()
                .filter(d -> args[0].equals(d.getDetectionSource())
                        && ((Collection<?>) args[1]).contains(d.getStatus())).toList());
        disruptions.query("existsByDetectionKeyAndResolvedAtAfter", (rows, args) -> rows.stream()
                .anyMatch(d -> args[0].equals(d.getDetectionKey()) && d.getResolvedAt() != null
                        && d.getResolvedAt().isAfter((LocalDateTime) args[1])));

        airports = InMemoryRepository.of(Airport::getAirportId, a -> a.setAirportId(UUID.randomUUID()));
        airports.query("findAllByOrderByIataCodeAsc", (rows, args) -> rows.stream()
                .sorted(Comparator.comparing(Airport::getIataCode)).toList());
        reports = InMemoryRepository.of(WeatherReport::getReportId, r -> r.setReportId(UUID.randomUUID()));
        reports.query("findFirstByAirportIdAndKindOrderByValidAtDesc", (rows, args) -> rows.stream()
                .filter(r -> r.getAirportId().equals(args[0]) && r.getKind() == args[1])
                .max(Comparator.comparing(WeatherReport::getValidAt)));
        bom = airport("BOM");
        del = airport("DEL");

        instances = InMemoryRepository.of(FlightInstance::getFlightId, i -> i.setFlightId(UUID.randomUUID()));
        instances.query("findLateFlights", (rows, args) -> rows.stream()
                .filter(i -> !i.getSchedule().getScheduledDeparture().isBefore((Instant) args[0])
                        && i.getSchedule().getScheduledDeparture().isBefore((Instant) args[1])
                        && !((Collection<?>) args[4]).contains(i.getStatus())
                        && (i.getDelayMinutes() >= (Integer) args[2] || i.getStatus() == args[3]))
                .toList());
        instances.query("findFlightsWithUnavailableAircraft", (rows, args) -> rows.stream()
                .filter(i -> i.getAircraft() != null
                        && !i.getSchedule().getScheduledDeparture().isBefore((Instant) args[0])
                        && i.getSchedule().getScheduledDeparture().isBefore((Instant) args[1])
                        && ((Collection<?>) args[2]).contains(i.getStatus())
                        && ((Collection<?>) args[3]).contains(i.getAircraft().getStatus()))
                .toList());
        instances.query("findGraphFlightsByIds", (rows, args) -> rows.stream()
                .filter(i -> ((Collection<?>) args[0]).contains(i.getFlightId())).toList());
        dependencies = InMemoryRepository.of(FlightDependency::getDependencyId,
                d -> d.setDependencyId(UUID.randomUUID()));
        dependencies.query("findActiveOutgoing", (rows, args) -> rows.stream()
                .filter(d -> Boolean.TRUE.equals(d.getActive())
                        && ((Collection<?>) args[0]).contains(d.getSourceFlight().getFlightId()))
                .toList());
    }

    private Airport airport(String iata) {
        return airports.as(AirportRepository.class).save(Airport.builder()
                .airportId(UUID.nameUUIDFromBytes(iata.getBytes())).iataCode(iata).airportName(iata).city(iata)
                .timezone("Asia/Kolkata").latitude(19.0).longitude(72.8).status(AirportStatus.OPERATIONAL).build());
    }

    private DisruptionMonitorService monitor(DataSourceMode mode, MonitoringSettings settings) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.systemDefault();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        DataSourcePolicy policy = new DataSourcePolicy(mode);
        // No API key: the monitor reads stored observations only and never calls the weather source.
        WeatherService weather = new WeatherService(airports.as(AirportRepository.class),
                reports.as(WeatherReportRepository.class),
                new OpenWeatherClient("http://127.0.0.1:1", "", Duration.ofSeconds(1)), clock,
                Duration.ofMinutes(30), Duration.ofMinutes(120), false, Duration.ofMinutes(180), 4);
        NetworkMonitorReader network = new NetworkMonitorReader(instances.as(FlightInstanceRepository.class),
                dependencies.as(FlightDependencyRepository.class), policy);
        return new DisruptionMonitorService(flights.as(FlightRepository.class),
                disruptions.as(DisruptionRepository.class), weather, network, policy, settings, clock);
    }

    private DisruptionMonitorService monitor() {
        return monitor(DataSourceMode.SYNTHETIC, MonitoringSettings.defaults());
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(now, ZoneId.systemDefault());
    }

    private Flight flight(String id, String origin, int departsInMinutes) {
        return flights.as(FlightRepository.class).save(Flight.builder().flightId(id).origin(origin)
                .destination("DEL").scheduledDeparture(localNow().plusMinutes(departsInMinutes))
                .status(Flight.FlightStatus.SCHEDULED).crewDutyHours(5.0).delayProbability(0.1)
                .dataOrigin(DataOrigin.SYNTHETIC).build());
    }

    private void observed(Airport airport, int score, String description, int minutesAgo) {
        reports.as(WeatherReportRepository.class).save(WeatherReport.builder().airportId(airport.getAirportId())
                .kind(WeatherReport.Kind.OBSERVATION).validAt(now.minus(Duration.ofMinutes(minutesAgo)))
                .fetchedAt(now.minus(Duration.ofMinutes(minutesAgo))).severityScore(score)
                .description(description).dataSource(DataSources.OPENWEATHERMAP).build());
    }

    private Disruption only() {
        assertEquals(1, disruptions.rows().size());
        return disruptions.rows().get(0);
    }

    @Test
    void aDetectionIsStoredAsDetectedAndNothingIsExecuted() {
        Flight flight = flight("AI-101", "BOM", 60);
        observed(bom, 3, "thunderstorm with heavy rain", 8);

        MonitorCycleResult result = monitor().runCycle();

        assertEquals(1, result.detected());
        assertEquals(1, result.opened());
        assertNull(result.error());
        Disruption disruption = only();
        assertEquals(DisruptionStatus.DETECTED, disruption.getStatus());
        assertEquals(DisruptionType.WEATHER_DELAY, disruption.getType());
        assertEquals(DisruptionSeverity.HIGH, disruption.getSeverity());
        assertEquals(0.85, disruption.getConfidence());
        assertEquals(DisruptionService.SOURCE_MONITOR, disruption.getDetectionSource());
        assertEquals(LegacyFlightRules.RULE_WEATHER, disruption.getDetectionRule());
        assertEquals("AI-101", disruption.getFlightId());
        assertEquals("BOM", disruption.getOriginAirport());
        assertEquals("DEL", disruption.getDestinationAirport());
        assertEquals(DataOrigin.SYNTHETIC, disruption.getDataOrigin());
        assertEquals(localNow(), disruption.getDetectedAt());
        assertNotNull(disruption.getDetectionKey());
        assertTrue(disruption.isActionable());
        // Detection only: no proposal, no resolution, and the flight is exactly as it was.
        assertNull(disruption.getResolutionJson());
        assertNull(disruption.getProposedAt());
        assertNull(disruption.getApprovedAt());
        assertEquals(Flight.FlightStatus.SCHEDULED, flight.getStatus());
        assertEquals(1, flights.saves(), "the monitor must not write to flights");
    }

    @Test
    void theSameEventIsRefreshedNotOpenedAgain() {
        flight("AI-101", "BOM", 60);
        observed(bom, 2, "moderate rain", 5);
        DisruptionMonitorService monitor = monitor();
        monitor.runCycle();

        now = now.plus(Duration.ofMinutes(2));
        observed(bom, 3, "heavy intensity rain", 1);          // it got worse
        MonitorCycleResult second = monitor.runCycle();
        now = now.plus(Duration.ofMinutes(2));
        MonitorCycleResult third = monitor.runCycle();

        assertEquals(0, second.opened());
        assertEquals(1, second.refreshed());
        assertEquals(1, third.refreshed());
        Disruption disruption = only();
        assertEquals(DisruptionSeverity.HIGH, disruption.getSeverity());
        assertEquals(90, disruption.getDelayMinutes());
        assertEquals(localNow(), disruption.getLastObservedAt());
        assertEquals(localNow().minusMinutes(4), disruption.getDetectedAt());
    }

    @Test
    void aFlightThatIsAlreadyBeingHandledGetsNoSecondDisruption() {
        flight("AI-101", "BOM", 60);
        observed(bom, 3, "thunderstorm", 5);
        disruptions.as(DisruptionRepository.class).save(Disruption.builder().flightId("AI-101")
                .type(DisruptionType.AIRCRAFT_FAULT).status(DisruptionStatus.PROPOSED)
                .detectionSource(DisruptionService.SOURCE_MANUAL).detectedAt(localNow()).build());

        MonitorCycleResult result = monitor().runCycle();

        assertEquals(1, result.detected());
        assertEquals(0, result.opened());
        assertEquals(1, result.suppressed());
        assertEquals(1, disruptions.rows().size());
    }

    @Test
    void aDisruptionNoLongerDetectedIsClosedUnlessSomeoneIsWorkingOnIt() {
        flight("AI-101", "BOM", 60);
        flight("AI-201", "DEL", 60);
        observed(bom, 3, "thunderstorm", 5);
        observed(del, 3, "dense fog", 5);
        DisruptionMonitorService monitor = monitor();
        monitor.runCycle();
        assertEquals(2, disruptions.rows().size());
        Disruption proposed = disruptions.rows().stream()
                .filter(d -> d.getFlightId().equals("AI-201")).findFirst().orElseThrow();
        proposed.setStatus(DisruptionStatus.PROPOSED);          // a controller asked for a proposal

        // The weather clears at both airports.
        now = now.plus(Duration.ofMinutes(5));
        observed(bom, 0, "clear sky", 1);
        observed(del, 0, "clear sky", 1);
        MonitorCycleResult soon = monitor.runCycle();
        assertEquals(0, soon.cleared(), "not before the clear-after time has passed");

        now = now.plus(Duration.ofMinutes(11));
        observed(bom, 0, "clear sky", 1);
        observed(del, 0, "clear sky", 1);
        MonitorCycleResult later = monitor.runCycle();

        assertEquals(1, later.cleared());
        Disruption cleared = disruptions.rows().stream()
                .filter(d -> d.getFlightId().equals("AI-101")).findFirst().orElseThrow();
        assertEquals(DisruptionStatus.RESOLVED, cleared.getStatus());
        assertEquals(DisruptionMonitorService.CLEARED_NOTE, cleared.getResolutionNote());
        assertEquals(localNow(), cleared.getResolvedAt());
        assertNull(cleared.getApprovedAt());
        assertEquals(DisruptionStatus.PROPOSED, proposed.getStatus());
        assertNull(proposed.getResolvedAt());
    }

    @Test
    void anEventClosedRecentlyIsNotRaisedAgainUntilTheCooldownHasPassed() {
        flight("AI-101", "BOM", 170);
        observed(bom, 3, "thunderstorm", 5);
        DisruptionMonitorService monitor = monitor(DataSourceMode.SYNTHETIC,
                new MonitoringSettings(Duration.ofHours(3), 2, 0.8, 11.0, 12.0, 15, Duration.ofHours(12),
                        Duration.ofHours(6), 4, 25, Duration.ofMinutes(60), Duration.ofMinutes(10)));
        monitor.runCycle();
        Disruption first = only();
        first.setStatus(DisruptionStatus.RESOLVED);             // approved and resolved by a controller
        first.setResolvedAt(localNow());

        now = now.plus(Duration.ofMinutes(30));
        observed(bom, 3, "thunderstorm", 2);
        MonitorCycleResult inCooldown = monitor.runCycle();
        assertEquals(0, inCooldown.opened());
        assertEquals(1, inCooldown.suppressed());

        now = now.plus(Duration.ofMinutes(31));
        observed(bom, 3, "thunderstorm", 2);
        assertEquals(1, monitor.runCycle().opened());
        assertEquals(2, disruptions.rows().size());
    }

    @Test
    void theMostSevereDetectionsAreKeptWhenACycleFindsTooMany() {
        observed(bom, 2, "moderate rain", 5);
        observed(del, 3, "dense fog", 5);
        flight("AI-1", "BOM", 30);
        flight("AI-2", "BOM", 40);
        flight("AI-3", "DEL", 50);
        MonitoringSettings twoPerCycle = new MonitoringSettings(Duration.ofHours(3), 2, 0.8, 11.0, 12.0, 15,
                Duration.ofHours(12), Duration.ofHours(6), 4, 2, Duration.ofHours(6), Duration.ofMinutes(10));
        DisruptionMonitorService monitor = monitor(DataSourceMode.SYNTHETIC, twoPerCycle);

        MonitorCycleResult first = monitor.runCycle();

        assertEquals(3, first.detected());
        assertEquals(2, first.opened());
        assertEquals(1, first.suppressed());
        assertTrue(disruptions.rows().stream().anyMatch(d -> d.getFlightId().equals("AI-3")
                && d.getSeverity() == DisruptionSeverity.HIGH), "the severe one is stored first");

        assertEquals(1, monitor.runCycle().opened());           // the rest follows next cycle
        assertEquals(3, disruptions.rows().size());
    }

    @Test
    void aDuplicateRefusedByTheDatabaseIsNotAnError() {
        flight("AI-101", "BOM", 60);
        observed(bom, 3, "thunderstorm", 5);
        disruptions.failNextSave(new DataIntegrityViolationException("uk_disruptions_open_detection"));

        MonitorCycleResult result = monitor().runCycle();

        assertNull(result.error());
        assertEquals(0, result.opened());
        assertEquals(1, result.suppressed());
    }

    @Test
    void syntheticFlightsAreNotMonitoredInLiveMode() {
        flight("AI-101", "BOM", 60);
        observed(bom, 3, "thunderstorm", 5);

        MonitorCycleResult result = monitor(DataSourceMode.LIVE, MonitoringSettings.defaults()).runCycle();

        assertEquals(0, result.detected());
        assertEquals(0, disruptions.rows().size());
    }

    @Test
    void aStaleObservationOrAForecastRaisesNothing() {
        flight("AI-101", "BOM", 60);
        observed(bom, 3, "thunderstorm", 180);                  // older than the accepted age
        reports.as(WeatherReportRepository.class).save(WeatherReport.builder().airportId(bom.getAirportId())
                .kind(WeatherReport.Kind.FORECAST).validAt(now.plus(Duration.ofHours(1))).fetchedAt(now)
                .severityScore(3).description("heavy thunderstorm").dataSource(DataSources.OPENWEATHERMAP).build());

        assertEquals(0, monitor().runCycle().detected());
        assertEquals(0, disruptions.rows().size());
    }

    // ── operational network (advisory) ───────────────────────────────────────

    private FlightInstance networkFlight(String number, Airport from, Airport to, int departsInMinutes,
                                         int delayMinutes, FlightInstanceStatus status, DataOrigin origin,
                                         Aircraft aircraft) {
        Instant departure = now.plus(Duration.ofMinutes(departsInMinutes));
        FlightSchedule schedule = FlightSchedule.builder().scheduleId(UUID.randomUUID()).flightNumber(number)
                .origin(from).destination(to).scheduledDeparture(departure)
                .scheduledArrival(departure.plus(Duration.ofMinutes(120))).operatingAirline("AI")
                .aircraftType("A320").serviceDate(LocalDate.of(2026, 11, 10)).dataOrigin(origin).build();
        return instances.as(FlightInstanceRepository.class).save(FlightInstance.builder().schedule(schedule)
                .status(status).delayMinutes(delayMinutes).aircraft(aircraft).build());
    }

    private void rotation(FlightInstance source, FlightInstance target) {
        dependencies.as(FlightDependencyRepository.class).save(FlightDependency.builder().sourceFlight(source)
                .targetFlight(target).dependencyType(DependencyType.AIRCRAFT_ROTATION).impactWeight(0.9)
                .minimumConnectionMinutes(35).active(true).dataOrigin(DataOrigin.MANUAL).build());
    }

    @Test
    void aLateNetworkFlightRaisesAdvisoryDisruptionsForItselfAndItsRotation() {
        FlightInstance inbound = networkFlight("AI101", bom, del, -30, 60, FlightInstanceStatus.DEPARTED,
                DataOrigin.MANUAL, null);
        FlightInstance outbound = networkFlight("AI102", del, bom, 120, 0, FlightInstanceStatus.SCHEDULED,
                DataOrigin.MANUAL, null);
        rotation(inbound, outbound);

        MonitorCycleResult result = monitor().runCycle();

        assertEquals(2, result.opened());
        Disruption delay = disruptions.rows().stream()
                .filter(d -> d.getType() == DisruptionType.FLIGHT_DELAY).findFirst().orElseThrow();
        assertEquals(inbound.getFlightId(), delay.getFlightInstanceId());
        assertEquals("BOM", delay.getOriginAirport());
        assertEquals(60, delay.getDelayMinutes());

        // Inbound arrives 01:30 + 60 = 02:30 after now, ready +35 = 03:05; outbound due out 02:00: 65 min.
        Disruption conflict = disruptions.rows().stream()
                .filter(d -> d.getType() == DisruptionType.TURNAROUND_CONFLICT).findFirst().orElseThrow();
        assertEquals(outbound.getFlightId(), conflict.getFlightInstanceId());
        assertEquals("AI102", conflict.getFlightId());
        assertEquals("DEL", conflict.getOriginAirport());
        assertEquals("BOM", conflict.getDestinationAirport());
        assertEquals(65, conflict.getDelayMinutes());
        assertEquals(DataOrigin.MANUAL, conflict.getDataOrigin());
        assertEquals(DisruptionStatus.DETECTED, conflict.getStatus());
        assertTrue(!conflict.isActionable(), "network disruptions are advisory");
        // The flights themselves are untouched.
        assertEquals(0, outbound.getDelayMinutes());
        assertEquals(FlightInstanceStatus.SCHEDULED, outbound.getStatus());
    }

    @Test
    void anUnavailableAircraftIsFlaggedAndSyntheticNetworkFlightsFollowTheDataMode() {
        Aircraft grounded = Aircraft.builder().aircraftId(UUID.randomUUID()).registration("VT-ABC")
                .aircraftType("A320").operatingAirline("AI").status(AircraftStatus.GROUNDED)
                .dataOrigin(DataOrigin.SYNTHETIC).build();
        networkFlight("AI301", bom, del, 90, 0, FlightInstanceStatus.SCHEDULED, DataOrigin.SYNTHETIC, grounded);

        assertEquals(0, monitor(DataSourceMode.LIVE, MonitoringSettings.defaults()).runCycle().detected());

        MonitorCycleResult hybrid = monitor(DataSourceMode.HYBRID, MonitoringSettings.defaults()).runCycle();
        assertEquals(1, hybrid.opened());
        Disruption disruption = only();
        assertEquals(DisruptionType.AIRCRAFT_UNAVAILABLE, disruption.getType());
        assertEquals(DataOrigin.SYNTHETIC, disruption.getDataOrigin());
        assertEquals(NetworkRules.RULE_AIRCRAFT_UNAVAILABLE, disruption.getDetectionRule());
    }
}
