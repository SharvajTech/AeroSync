package com.aerosync.ingestion;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.datasource.DataSources;
import com.aerosync.ingestion.StubHttpServer.Reply;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.IngestionCheckpoint;
import com.aerosync.model.IngestionRun;
import com.aerosync.model.LiveAircraftState;
import com.aerosync.model.ScheduleBasis;
import com.aerosync.repository.FlightScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.aerosync.ingestion.IngestionFixture.flight;
import static com.aerosync.ingestion.IngestionFixture.state;
import static com.aerosync.ingestion.IngestionFixture.states;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ingestion pipeline end to end (mocked OpenSky → validation →
 * normalization → storage): idempotency, duplicates, provenance, partial
 * failures, rate limits and backoff.
 */
class OpenSkyIngestionJobTest {

    private static final String STATES = "/api/states/all";
    private static final String DEPARTURES = "/api/flights/departure";

    private IngestionFixture fx;
    private long now;

    @BeforeEach
    void setUp() {
        fx = new IngestionFixture();
        now = fx.clock.instant().getEpochSecond();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    // ── live states ─────────────────────────────────────────────────────────

    @Test
    void aPollStoresValidatedStatesWithSourceTimestampsAndAirport() {
        fx.openSky.on(STATES, Reply.of(200, states(now,
                state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false),
                state("800c6e", "AIC5BH  ", 28.60, 77.20, now - 9, true),
                state("800c6f", "SEJ8001 ", 23.50, 75.50, now - 2, false))));

        IngestionRun run = fx.job(DataSourceMode.HYBRID).pollStates();

        assertEquals(IngestionRun.Status.SUCCESS, run.getStatus());
        assertEquals(3, run.getRecordsFetched());
        assertEquals(3, run.getRecordsInserted());
        assertEquals(DataSources.OPENSKY, run.getDataSource());
        assertEquals(OpenSkyIngestionJob.FEED_STATES, run.getFeed());
        assertEquals(1, run.getApiCalls());

        LiveAircraftState indigo = row("800c6d");
        assertEquals("IGO1454", indigo.getCallsign());
        assertEquals("6E1454", indigo.getFlightNumber());
        assertEquals(fx.bom.getAirportId(), indigo.getNearestAirportId());
        assertEquals(DataSources.OPENSKY, indigo.getDataSource());
        assertEquals(Instant.ofEpochSecond(now - 4), indigo.getLastContact());
        assertEquals(Instant.ofEpochSecond(now), indigo.getSnapshotTime());
        assertEquals(fx.clock.instant(), indigo.getIngestedAt());

        LiveAircraftState airIndia = row("800c6e");
        assertNull(airIndia.getFlightNumber());              // ATC callsign: no flight number is derived
        assertEquals(fx.del.getAirportId(), airIndia.getNearestAirportId());
        assertTrue(airIndia.isOnGround());

        assertNull(row("800c6f").getNearestAirportId());     // en route, far from every airport
    }

    @Test
    void pollingTheSameSnapshotAgainChangesNothing() {
        fx.openSky.on(STATES, Reply.of(200, states(now,
                state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false),
                state("800c6e", "AIC101  ", 28.60, 77.20, now - 9, true))));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);

        job.pollStates();
        int savesAfterFirst = fx.states.saves();
        IngestionRun second = job.pollStates();

        assertEquals(2, fx.states.rows().size());
        assertEquals(0, second.getRecordsInserted());
        assertEquals(0, second.getRecordsUpdated());
        assertEquals(2, second.getRecordsUnchanged());
        assertEquals(savesAfterFirst, fx.states.saves(), "an identical snapshot must not write");
    }

    @Test
    void aNewerStateUpdatesTheAircraftInPlaceAndAnOlderOneIsIgnored() {
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);
        fx.openSky.on(STATES, Reply.of(200, states(now, state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false))));
        job.pollStates();

        fx.clock.advance(Duration.ofSeconds(60));
        fx.openSky.on(STATES, Reply.of(200,
                states(now + 60, state("800c6d", "IGO1454 ", 19.10, 72.88, now + 58, true))));
        IngestionRun update = job.pollStates();

        assertEquals(1, update.getRecordsUpdated());
        assertEquals(1, fx.states.rows().size());
        assertEquals(19.10, row("800c6d").getLatitude());
        assertTrue(row("800c6d").isOnGround());

        // A late, older message for the same aircraft does not overwrite the newer state.
        fx.openSky.on(STATES, Reply.of(200,
                states(now + 61, state("800c6d", "IGO1454 ", 19.30, 73.10, now + 20, false))));
        IngestionRun stale = job.pollStates();

        assertEquals(1, stale.getRecordsUnchanged());
        assertEquals(19.10, row("800c6d").getLatitude());
    }

    @Test
    void incompleteAndStaleRecordsAreSkippedAndTheReasonIsRecorded() {
        fx.openSky.on(STATES, Reply.of(200, states(now,
                state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false),
                "[\"800c70\",\"IGO22   \",\"India\",null," + (now - 3)
                        + ",null,null,null,false,null,null,null,null,null,null,false,0]",
                state("800c71", "IGO33   ", 19.20, 72.95, now - 900, false),
                "[\"broken\"]")));

        IngestionRun run = fx.job(DataSourceMode.LIVE).pollStates();

        assertEquals(IngestionRun.Status.SUCCESS, run.getStatus());
        assertEquals(4, run.getRecordsFetched());
        assertEquals(1, run.getRecordsInserted());
        assertEquals(3, run.getRecordsSkipped());
        assertTrue(run.getDetails().contains("NO_POSITION=1"), run.getDetails());
        assertTrue(run.getDetails().contains("STALE=1"), run.getDetails());
        assertTrue(run.getDetails().contains("MALFORMED=1"), run.getDetails());
        assertEquals(1, fx.states.rows().size());
    }

    @Test
    void statesNotHeardFromForTheRetentionTimeAreRemoved() {
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);
        fx.openSky.on(STATES, Reply.of(200, states(now, state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false))));
        job.pollStates();

        fx.clock.advance(Duration.ofHours(7));
        long later = fx.clock.instant().getEpochSecond();
        fx.openSky.on(STATES, Reply.of(200,
                states(later, state("800c6e", "AIC101  ", 28.60, 77.20, later - 3, false))));
        job.pollStates();

        assertEquals(List.of("800c6e"), fx.states.rows().stream().map(LiveAircraftState::getIcao24).toList());
    }

    @Test
    void aRateLimitMakesTheFeedWaitAsLongAsOpenSkyAsks() {
        fx.openSky.on(STATES, Reply.of(429, "").header("X-Rate-Limit-Retry-After-Seconds", "3600"));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);

        IngestionRun limited = job.pollStatesIfDue().orElseThrow();
        assertEquals(IngestionRun.Status.RATE_LIMITED, limited.getStatus());
        assertEquals(1, fx.openSky.received(STATES).size());

        // Due again by the normal interval, but OpenSky asked for an hour: no request is made.
        fx.clock.advance(Duration.ofMinutes(30));
        assertTrue(job.pollStatesIfDue().isEmpty());
        assertEquals(1, fx.openSky.received(STATES).size());

        fx.clock.advance(Duration.ofMinutes(31));
        fx.openSky.on(STATES, Reply.of(200, states(fx.clock.instant().getEpochSecond())));
        assertEquals(IngestionRun.Status.SUCCESS, job.pollStatesIfDue().orElseThrow().getStatus());
        assertEquals(2, fx.openSky.received(STATES).size());
    }

    @Test
    void failuresBackOffExponentiallyAndKeepWhatWasStored() {
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);
        fx.openSky.on(STATES, Reply.of(200, states(now, state("800c6d", "IGO1454 ", 19.20, 72.95, now - 4, false))));
        job.pollStatesIfDue();
        assertEquals(1, fx.states.rows().size());

        // Anonymous access and a 2-credit box: the budget allows a poll every 480 s.
        assertEquals(Duration.ofSeconds(480), job.statesFeed().effectiveInterval());
        fx.openSky.on(STATES, Reply.of(503, ""));
        fx.clock.advance(Duration.ofSeconds(480));
        IngestionRun failed = job.pollStatesIfDue().orElseThrow();

        assertEquals(IngestionRun.Status.FAILED, failed.getStatus());
        assertEquals(2, failed.getApiCalls());              // two attempts inside the client
        assertNotNull(failed.getErrorMessage());
        assertEquals(1, fx.states.rows().size(), "a failed poll must not remove stored states");
        assertEquals(1, job.statesFeed().consecutiveFailures());

        // After a failure the feed waits at least its normal interval; nothing is tried before.
        int requests = fx.openSky.received(STATES).size();
        fx.clock.advance(Duration.ofSeconds(400));
        assertTrue(job.pollStatesIfDue().isEmpty());
        assertEquals(requests, fx.openSky.received(STATES).size());

        fx.clock.advance(Duration.ofSeconds(81));
        fx.openSky.on(STATES, Reply.of(200, states(fx.clock.instant().getEpochSecond())));
        assertEquals(IngestionRun.Status.SUCCESS, job.pollStatesIfDue().orElseThrow().getStatus());
        assertEquals(0, job.statesFeed().consecutiveFailures());
    }

    @Test
    void theFailureBackoffDoublesUpToItsMaximum() {
        FeedState feed = new FeedState("TEST");
        Instant start = Instant.parse("2026-11-10T10:00:00Z");
        Duration interval = Duration.ofSeconds(30);
        Duration base = Duration.ofSeconds(60);
        Duration max = Duration.ofSeconds(200);

        feed.failed(start, interval, base, max, "x");
        assertEquals(start.plusSeconds(60), feed.nextAttemptAt());
        feed.failed(start, interval, base, max, "x");
        assertEquals(start.plusSeconds(120), feed.nextAttemptAt());
        feed.failed(start, interval, base, max, "x");
        assertEquals(start.plusSeconds(200), feed.nextAttemptAt());     // capped
        assertEquals(3, feed.consecutiveFailures());

        feed.succeeded(start, interval, 5, IngestionRun.Status.SUCCESS);
        assertEquals(start.plusSeconds(30), feed.nextAttemptAt());
        assertEquals(0, feed.consecutiveFailures());
        assertEquals(5, feed.recordsStored());
    }

    @Test
    void nothingIsFetchedInSyntheticModeOrWhenSwitchedOff() {
        fx.openSky.on(STATES, Reply.of(200, states(now)));

        assertTrue(fx.job(DataSourceMode.SYNTHETIC).pollStatesIfDue().isEmpty());
        assertTrue(fx.job(DataSourceMode.SYNTHETIC).importFlightLegsIfDue().isEmpty());
        assertTrue(fx.job(DataSourceMode.LIVE, fx.settings(false)).pollStatesIfDue().isEmpty());
        assertFalse(fx.job(DataSourceMode.SYNTHETIC).active());

        assertEquals(0, fx.openSky.received().size());
        assertEquals(0, fx.runs.rows().size());
    }

    // ── flight legs ─────────────────────────────────────────────────────────

    /** Yesterday (UTC) for the fixture clock is 2026-11-09; BOM departures answer with two legs. */
    private void departuresFromMumbai() {
        fx.openSky.on(DEPARTURES, Reply.of(200, "["
                + flight("800c6d", "IGO0679 ", "VABB", "VIDP", "2026-11-09T04:10:00Z", "2026-11-09T06:15:00Z") + ","
                + flight("800c6e", "AIC5BH  ", "VABB", "VIDP", "2026-11-09T05:00:00Z", "2026-11-09T07:05:00Z") + ","
                + flight("800c6f", "SEJ8001 ", "VABB", "OMDB", "2026-11-09T05:30:00Z", "2026-11-09T08:40:00Z") + ","
                + flight("800c70", "AIC101  ", "VABB", "VAPO", "2026-11-09T09:00:00Z", "2026-11-09T09:35:00Z")
                + "]"));
    }

    @Test
    void legsAreImportedIntoTheFlightTablesLabelledAsObservedOpenSkyData() {
        departuresFromMumbai();

        IngestionRun run = fx.job(DataSourceMode.HYBRID).importFlightLegs().orElseThrow();

        // Three airports and two past days: six requests, all answered with the same body here.
        assertEquals(6, run.getApiCalls());
        assertEquals(IngestionRun.Status.SUCCESS, run.getStatus());
        assertEquals(2, fx.schedules.rows().size());
        assertTrue(run.getDetails().contains("UNMAPPED_CALLSIGN"), run.getDetails());
        assertTrue(run.getDetails().contains("AIRPORT_NOT_IN_NETWORK"), run.getDetails());

        FlightSchedule leg = schedule("6E679");
        assertEquals(DataOrigin.IMPORTED, leg.getDataOrigin());
        assertEquals(DataSources.OPENSKY, leg.getDataSource());
        assertEquals(ScheduleBasis.OBSERVED, leg.getScheduleBasis());
        assertEquals("800c6d:" + Instant.parse("2026-11-09T04:10:00Z").getEpochSecond(), leg.getSourceRecordId());
        assertEquals("IGO0679", leg.getCallsign());
        assertEquals("800c6d", leg.getAircraftIcao24());
        assertEquals("BOM", leg.getOrigin().getIataCode());
        assertEquals("DEL", leg.getDestination().getIataCode());
        assertEquals(LocalDate.of(2026, 11, 9), leg.getServiceDate());
        assertEquals(Instant.parse("2026-11-09T04:10:00Z"), leg.getScheduledDeparture());
        assertEquals(fx.clock.instant(), leg.getIngestedAt());
        assertNull(leg.getAircraftType(), "OpenSky does not report the aircraft type");

        FlightInstance instance = fx.instances.rows().stream()
                .filter(i -> i.getSchedule() == leg).findFirst().orElseThrow();
        assertEquals(FlightInstanceStatus.ARRIVED, instance.getStatus());
        assertEquals(Instant.parse("2026-11-09T04:10:00Z"), instance.getActualDeparture());
        assertEquals(Instant.parse("2026-11-09T06:15:00Z"), instance.getActualArrival());
        assertEquals(0, instance.getDelayMinutes());
    }

    @Test
    void aPeriodThatWasImportedIsNeverFetchedAgain() {
        departuresFromMumbai();
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID);
        job.importFlightLegs();
        int requests = fx.openSky.received(DEPARTURES).size();

        fx.clock.advance(Duration.ofHours(2));
        Optional<IngestionRun> second = job.importFlightLegsIfDue();

        assertTrue(second.isEmpty(), "nothing left to fetch, so no run is recorded");
        assertEquals(requests, fx.openSky.received(DEPARTURES).size());
        assertEquals(6, fx.checkpoints.rows().size());
        assertTrue(fx.checkpoints.rows().stream()
                .allMatch(c -> c.getStatus() == IngestionCheckpoint.Status.COMPLETE));
    }

    @Test
    void importingTheSameRecordsAgainCreatesNoDuplicates() {
        departuresFromMumbai();
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID);
        job.importFlightLegs();
        int schedules = fx.schedules.rows().size();
        int scheduleSaves = fx.schedules.saves();

        // Lose the checkpoints (as after a restore): everything is fetched and offered again.
        fx.checkpoints.removeIf(checkpoint -> true);
        fx.clock.advance(Duration.ofHours(2));
        IngestionRun again = job.importFlightLegs().orElseThrow();

        assertEquals(schedules, fx.schedules.rows().size());
        assertEquals(schedules, fx.instances.rows().size());
        assertEquals(0, again.getRecordsInserted());
        assertEquals(0, again.getRecordsUpdated());
        assertEquals(scheduleSaves, fx.schedules.saves(), "unchanged records must not be written");
    }

    @Test
    void aChangedRecordUpdatesTheStoredLegInsteadOfAddingOne() {
        fx.openSky.on(DEPARTURES, Reply.of(200, "["
                + flight("800c6d", "IGO0679 ", "VABB", "VIDP", "2026-11-09T04:10:00Z", "2026-11-09T06:15:00Z") + "]"));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID);
        job.importFlightLegs();

        fx.checkpoints.removeIf(checkpoint -> true);
        fx.clock.advance(Duration.ofHours(2));
        fx.openSky.on(DEPARTURES, Reply.of(200, "["
                + flight("800c6d", "IGO0679 ", "VABB", "VIDP", "2026-11-09T04:10:00Z", "2026-11-09T06:22:00Z") + "]"));
        IngestionRun update = job.importFlightLegs().orElseThrow();

        assertEquals(1, fx.schedules.rows().size());
        assertTrue(update.getRecordsUpdated() >= 1);
        FlightSchedule leg = schedule("6E679");
        assertEquals(Instant.parse("2026-11-09T06:22:00Z"), leg.getScheduledArrival());
        assertEquals(fx.clock.instant(), leg.getSourceUpdatedAt());
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), leg.getIngestedAt());
        assertEquals(Instant.parse("2026-11-09T06:22:00Z"), fx.instances.rows().get(0).getActualArrival());
    }

    @Test
    void aSyntheticLegWithTheSameKeyIsNeverOverwrittenOrRelabelled() {
        FlightSchedule synthetic = fx.schedules.as(FlightScheduleRepository.class).save(FlightSchedule.builder()
                .flightNumber("6E679")
                .origin(fx.bom)
                .destination(fx.del)
                .scheduledDeparture(Instant.parse("2026-11-09T03:00:00Z"))
                .scheduledArrival(Instant.parse("2026-11-09T05:10:00Z"))
                .operatingAirline("6E")
                .aircraftType("A320")
                .serviceDate(LocalDate.of(2026, 11, 9))
                .dataOrigin(DataOrigin.SYNTHETIC)
                .dataSource(DataSources.SYNTHETIC_GENERATOR)
                .scheduleBasis(ScheduleBasis.PLANNED)
                .build());
        departuresFromMumbai();

        IngestionRun run = fx.job(DataSourceMode.HYBRID).importFlightLegs().orElseThrow();

        assertTrue(run.getDetails().contains("LEG_EXISTS_FROM_OTHER_SOURCE"), run.getDetails());
        assertEquals(DataOrigin.SYNTHETIC, synthetic.getDataOrigin());
        assertEquals(DataSources.SYNTHETIC_GENERATOR, synthetic.getDataSource());
        assertEquals(Instant.parse("2026-11-09T03:00:00Z"), synthetic.getScheduledDeparture());
        assertNull(synthetic.getSourceRecordId());
        assertEquals(1, fx.schedules.rows().stream().filter(s -> s.getFlightNumber().equals("6E679")).count());
    }

    @Test
    void anEmptyAnswerForYesterdayIsRetriedLaterButOlderDaysAreClosed() {
        // No reply registered: the stand-in answers 404, as OpenSky does before its nightly batch.
        OpenSkyIngestionJob job = fx.job(DataSourceMode.LIVE);

        IngestionRun first = job.importFlightLegs().orElseThrow();

        assertEquals(IngestionRun.Status.SUCCESS, first.getStatus());
        assertEquals(0, first.getRecordsFetched());
        List<IngestionCheckpoint> yesterday = fx.checkpoints.rows().stream()
                .filter(c -> c.getPeriodStart().equals(LocalDate.of(2026, 11, 9))).toList();
        List<IngestionCheckpoint> dayBefore = fx.checkpoints.rows().stream()
                .filter(c -> c.getPeriodStart().equals(LocalDate.of(2026, 11, 8))).toList();
        assertEquals(3, yesterday.size());
        assertTrue(yesterday.stream().allMatch(c -> c.getStatus() == IngestionCheckpoint.Status.PENDING));
        assertTrue(dayBefore.stream().allMatch(c -> c.getStatus() == IngestionCheckpoint.Status.EMPTY));

        // Next cycle: only yesterday's three airports are asked again.
        fx.clock.advance(Duration.ofHours(1));
        departuresFromMumbai();
        IngestionRun second = job.importFlightLegs().orElseThrow();

        assertEquals(3, second.getApiCalls());
        assertEquals(2, fx.schedules.rows().size());
    }

    @Test
    void aStorageFailureForOneAirportDoesNotLoseTheOthers() {
        departuresFromMumbai();
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID);
        fx.schedules.failNextSave(new IllegalStateException("simulated database failure"));

        IngestionRun run = job.importFlightLegs().orElseThrow();

        assertEquals(IngestionRun.Status.PARTIAL, run.getStatus());
        assertEquals(2, fx.schedules.rows().size(), "later batches are stored");
        assertEquals(1, fx.checkpoints.rows().stream()
                .filter(c -> c.getStatus() == IngestionCheckpoint.Status.PENDING).count());

        // The failed airport and day is fetched again next cycle; everything else is not.
        fx.clock.advance(Duration.ofHours(1));
        IngestionRun retry = job.importFlightLegs().orElseThrow();

        assertEquals(1, retry.getApiCalls());
        assertEquals(IngestionRun.Status.SUCCESS, retry.getStatus());
        assertEquals(2, fx.schedules.rows().size());
        assertTrue(fx.checkpoints.rows().stream()
                .allMatch(c -> c.getStatus() == IngestionCheckpoint.Status.COMPLETE));
    }

    @Test
    void aRateLimitInTheMiddleKeepsWhatWasImportedAndResumesWhereItStopped() {
        fx.openSky.on(DEPARTURES,
                Reply.of(200, "[" + flight("800c6d", "IGO0679 ", "VABB", "VIDP",
                        "2026-11-09T04:10:00Z", "2026-11-09T06:15:00Z") + "]"),
                Reply.of(429, "").header("X-Rate-Limit-Retry-After-Seconds", "7200"));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID);

        IngestionRun limited = job.importFlightLegsIfDue().orElseThrow();

        assertEquals(IngestionRun.Status.RATE_LIMITED, limited.getStatus());
        assertEquals(1, fx.schedules.rows().size());
        assertEquals(1, fx.checkpoints.rows().size());
        assertEquals(2, fx.openSky.received(DEPARTURES).size());

        fx.clock.advance(Duration.ofMinutes(90));           // past the check interval, inside the wait
        assertTrue(job.importFlightLegsIfDue().isEmpty());
        assertEquals(2, fx.openSky.received(DEPARTURES).size());

        fx.clock.advance(Duration.ofMinutes(31));
        fx.openSky.on(DEPARTURES, Reply.of(200, "[]"));
        IngestionRun resumed = job.importFlightLegsIfDue().orElseThrow();
        assertEquals(5, resumed.getApiCalls(), "the airport and day already imported is not asked again");
    }

    @Test
    void oneCycleStaysWithinItsCallLimit() {
        departuresFromMumbai();
        IngestionSettings limited = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 2, 4, 0,
                Duration.ofSeconds(60), Duration.ofMinutes(30));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID, limited);

        IngestionRun first = job.importFlightLegs().orElseThrow();
        assertEquals(4, first.getApiCalls());
        assertEquals(IngestionRun.Status.PARTIAL, first.getStatus());

        IngestionRun second = job.importFlightLegs().orElseThrow();
        assertEquals(2, second.getApiCalls());
        assertEquals(IngestionRun.Status.SUCCESS, second.getStatus());
    }

    @Test
    void theFlightsCreditBudgetFollowsTheAccountType() {
        IngestionSettings settings = fx.settings(true);
        // 400 credits anonymous, 4000 with an account; 90 % planned; 30 credits per /flights query.
        assertEquals(12, settings.flightLegCallsPerDay(false));
        assertEquals(120, settings.flightLegCallsPerDay(true));
        IngestionSettings configured = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 2, 20, 300,
                Duration.ofSeconds(60), Duration.ofMinutes(30));
        assertEquals(9, configured.flightLegCallsPerDay(false));
    }

    @Test
    void flightLegCallsStopAtTheDailyCreditBudgetUntilTheNextUtcDay() {
        fx.openSky.on(DEPARTURES, Reply.of(200, "[]"));
        // Seven days for three airports would be 21 calls; an anonymous budget pays for 12 per day.
        IngestionSettings week = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 7, 50, 0,
                Duration.ofSeconds(60), Duration.ofMinutes(30));
        OpenSkyIngestionJob job = fx.job(DataSourceMode.HYBRID, week);

        IngestionRun first = job.importFlightLegs().orElseThrow();
        assertEquals(12, first.getApiCalls());
        assertEquals(IngestionRun.Status.PARTIAL, first.getStatus());
        assertTrue(first.getErrorMessage().contains("daily credit budget"), first.getErrorMessage());

        // Later the same UTC day: no further /flights call at all.
        fx.clock.advance(Duration.ofHours(2));
        assertTrue(job.importFlightLegs().isEmpty());
        assertEquals(12, fx.openSky.received(DEPARTURES).size());

        // After 00:00 UTC the budget is available again.
        fx.clock.advance(Duration.ofDays(1));
        IngestionRun nextDay = job.importFlightLegs().orElseThrow();
        assertTrue(nextDay.getApiCalls() > 0 && nextDay.getApiCalls() <= 12, "calls: " + nextDay.getApiCalls());
    }

    @Test
    void theDailyBudgetCountsCallsRecordedBeforeARestart() {
        fx.openSky.on(DEPARTURES, Reply.of(200, "[]"));
        IngestionSettings week = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 7, 50, 0,
                Duration.ofSeconds(60), Duration.ofMinutes(30));
        fx.job(DataSourceMode.HYBRID, week).importFlightLegs().orElseThrow();

        // A new job instance (as after a restart) reads today's calls from the run table.
        fx.clock.advance(Duration.ofMinutes(5));
        assertTrue(fx.job(DataSourceMode.HYBRID, week).importFlightLegs().isEmpty());
        assertEquals(12, fx.openSky.received(DEPARTURES).size());
    }

    private LiveAircraftState row(String icao24) {
        return fx.states.rows().stream().filter(s -> s.getIcao24().equals(icao24)).findFirst().orElseThrow();
    }

    private FlightSchedule schedule(String flightNumber) {
        return fx.schedules.rows().stream()
                .filter(s -> s.getFlightNumber().equals(flightNumber)).findFirst().orElseThrow();
    }
}
