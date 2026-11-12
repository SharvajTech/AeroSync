package com.aerosync.ingestion;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.ingestion.OpenSkyClientTest.MutableClock;
import com.aerosync.ingestion.opensky.OpenSkyClient;
import com.aerosync.ingestion.opensky.OpenSkySettings;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.IngestionCheckpoint;
import com.aerosync.model.IngestionRun;
import com.aerosync.model.LiveAircraftState;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import com.aerosync.repository.IngestionCheckpointRepository;
import com.aerosync.repository.IngestionRunRepository;
import com.aerosync.repository.LiveAircraftStateRepository;
import com.aerosync.testsupport.InMemoryRepository;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * TEST ONLY. The real ingestion pipeline (client, normalizer, stores, job)
 * wired by hand over in-memory repositories and a local stand-in for the
 * OpenSky API. No database, no Spring context, no outside request.
 */
final class IngestionFixture implements AutoCloseable {

    final StubHttpServer openSky = new StubHttpServer();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T10:00:00Z"));
    final List<Duration> sleeps = new ArrayList<>();

    final InMemoryRepository<Airport, UUID> airports =
            InMemoryRepository.of(Airport::getAirportId, a -> a.setAirportId(UUID.randomUUID()));
    final InMemoryRepository<LiveAircraftState, String> states =
            InMemoryRepository.of(LiveAircraftState::getIcao24, s -> { });
    final InMemoryRepository<FlightSchedule, UUID> schedules =
            InMemoryRepository.of(FlightSchedule::getScheduleId, s -> s.setScheduleId(UUID.randomUUID()));
    final InMemoryRepository<FlightInstance, UUID> instances =
            InMemoryRepository.of(FlightInstance::getFlightId, i -> i.setFlightId(UUID.randomUUID()));
    final InMemoryRepository<IngestionCheckpoint, UUID> checkpoints =
            InMemoryRepository.of(IngestionCheckpoint::getCheckpointId, c -> c.setCheckpointId(UUID.randomUUID()));
    final InMemoryRepository<IngestionRun, UUID> runs =
            InMemoryRepository.of(IngestionRun::getRunId, r -> r.setRunId(UUID.randomUUID()));

    final Airport bom = airport("BOM", "VABB", 19.0896, 72.8656);
    final Airport del = airport("DEL", "VIDP", 28.5562, 77.1000);
    final Airport pnq = airport("PNQ", "VAPO", 18.5821, 73.9197);

    IngestionFixture() {
        airports.query("findAllByOrderByIataCodeAsc", (rows, args) -> rows.stream()
                .sorted(Comparator.comparing(Airport::getIataCode)).toList());
        states.query("deleteOlderThan", (rows, args) ->
                states.removeIf(state -> state.getLastContact().isBefore((Instant) args[0])));
        schedules.query("findByDataSourceAndSourceRecordId", (rows, args) -> rows.stream()
                .filter(s -> args[0].equals(s.getDataSource()) && args[1].equals(s.getSourceRecordId()))
                .findFirst());
        schedules.query("findByFlightNumberAndServiceDateAndOrigin_AirportId", (rows, args) -> rows.stream()
                .filter(s -> s.getFlightNumber().equals(args[0]) && s.getServiceDate().equals(args[1])
                        && s.getOrigin().getAirportId().equals(args[2]))
                .findFirst());
        instances.query("findBySchedule_ScheduleId", (rows, args) -> rows.stream()
                .filter(i -> i.getSchedule().getScheduleId().equals(args[0])).findFirst());
        checkpoints.query("findByDataSourceAndFeedAndScopeKeyAndPeriodStart", (rows, args) -> rows.stream()
                .filter(c -> c.getDataSource().equals(args[0]) && c.getFeed().equals(args[1])
                        && c.getScopeKey().equals(args[2]) && c.getPeriodStart().equals(args[3]))
                .findFirst());
        runs.query("deleteOlderThan", (rows, args) ->
                runs.removeIf(run -> run.getStartedAt().isBefore((Instant) args[0])));
        runs.query("sumApiCallsSince", (rows, args) -> rows.stream()
                .filter(r -> r.getDataSource().equals(args[0]) && r.getFeed().equals(args[1])
                        && !r.getStartedAt().isBefore((Instant) args[2]))
                .mapToLong(IngestionRun::getApiCalls).sum());
        runs.query("findFirstByDataSourceAndFeedOrderByStartedAtDesc", (rows, args) -> rows.stream()
                .filter(r -> r.getDataSource().equals(args[0]) && r.getFeed().equals(args[1]))
                .max(Comparator.comparing(IngestionRun::getStartedAt)));
        runs.query("findFirstByDataSourceAndFeedAndStatusOrderByStartedAtDesc", (rows, args) -> rows.stream()
                .filter(r -> r.getDataSource().equals(args[0]) && r.getFeed().equals(args[1])
                        && r.getStatus() == args[2])
                .max(Comparator.comparing(IngestionRun::getStartedAt)));
    }

    private Airport airport(String iata, String icao, double latitude, double longitude) {
        Airport airport = Airport.builder()
                .airportId(UUID.nameUUIDFromBytes(iata.getBytes()))
                .iataCode(iata)
                .icaoCode(icao)
                .airportName(iata + " airport")
                .city(iata)
                .timezone("Asia/Kolkata")
                .latitude(latitude)
                .longitude(longitude)
                .status(AirportStatus.OPERATIONAL)
                .build();
        return airports.as(AirportRepository.class).save(airport);
    }

    IngestionSettings settings(boolean enabled) {
        return new IngestionSettings(enabled, Duration.ofSeconds(300), "", 0.75, Duration.ofMinutes(5), 60,
                Duration.ofHours(6), true, Duration.ofMinutes(60), 2, 20, 0, Duration.ofSeconds(60),
                Duration.ofMinutes(30));
    }

    OpenSkyIngestionJob job(DataSourceMode mode) {
        return job(mode, settings(true));
    }

    OpenSkyIngestionJob job(DataSourceMode mode, IngestionSettings settings) {
        OpenSkySettings openSkySettings = new OpenSkySettings(openSky.url() + "/api", openSky.url() + "/token",
                "", "", Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofMillis(10),
                Duration.ofMillis(20));
        OpenSkyClient client = new OpenSkyClient(openSkySettings,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), clock, sleeps::add);
        AirportRepository airportRepository = airports.as(AirportRepository.class);
        return new OpenSkyIngestionJob(
                new DataSourcePolicy(mode),
                settings,
                client,
                new AirportDirectoryProvider(airportRepository, clock),
                new CallsignMapper(),
                new LiveStateStore(states.as(LiveAircraftStateRepository.class)),
                new FlightLegStore(schedules.as(FlightScheduleRepository.class),
                        instances.as(FlightInstanceRepository.class), airportRepository),
                checkpoints.as(IngestionCheckpointRepository.class),
                new IngestionRunRecorder(runs.as(IngestionRunRepository.class)),
                clock);
    }

    /** JSON of one state vector in the documented order. */
    static String state(String icao24, String callsign, double latitude, double longitude, long lastContact,
                        boolean onGround) {
        return "[\"" + icao24 + "\",\"" + callsign + "\",\"India\"," + lastContact + "," + lastContact + ","
                + longitude + "," + latitude + ",900.0," + onGround + ",75.0,270.0,-3.0,null,950.0,null,false,0]";
    }

    static String states(long time, String... vectors) {
        return "{\"time\":" + time + ",\"states\":[" + String.join(",", vectors) + "]}";
    }

    /** JSON of one flight of the departures endpoint. */
    static String flight(String icao24, String callsign, String from, String to, String departure,
                         String arrival) {
        return "{\"icao24\":\"" + icao24 + "\",\"firstSeen\":" + Instant.parse(departure).getEpochSecond()
                + ",\"estDepartureAirport\":" + quote(from) + ",\"lastSeen\":" + Instant.parse(arrival).getEpochSecond()
                + ",\"estArrivalAirport\":" + quote(to) + ",\"callsign\":" + quote(callsign) + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Override
    public void close() {
        openSky.close();
    }
}
