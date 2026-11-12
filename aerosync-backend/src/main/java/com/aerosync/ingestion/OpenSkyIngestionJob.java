package com.aerosync.ingestion;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.datasource.DataSources;
import com.aerosync.ingestion.AirportDirectory.AirportRef;
import com.aerosync.ingestion.opensky.BoundingBox;
import com.aerosync.ingestion.opensky.OpenSkyClient;
import com.aerosync.ingestion.opensky.OpenSkyException;
import com.aerosync.ingestion.opensky.OpenSkyFlight;
import com.aerosync.ingestion.opensky.OpenSkyStateVector;
import com.aerosync.ingestion.opensky.OpenSkyStates;
import com.aerosync.model.IngestionCheckpoint;
import com.aerosync.model.IngestionRun;
import com.aerosync.repository.IngestionCheckpointRepository;
import com.aerosync.security.LogRedaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The OpenSky ingestion pipeline: fetch → validate → normalize → store, for
 * two feeds.
 *
 * <ul>
 *   <li>{@link #FEED_STATES}: live aircraft states inside one bounding box
 *       around the network's airports, polled at an interval the API credit
 *       budget can pay for.</li>
 *   <li>{@link #FEED_FLIGHT_LEGS}: yesterday's (and older) departures per
 *       airport. OpenSky derives flights in a nightly batch, so this feed is
 *       a day behind by design. A checkpoint per airport and day makes sure a
 *       period is fetched once.</li>
 * </ul>
 *
 * <p>Ingestion runs only in data mode LIVE or HYBRID and never on a request
 * thread ({@link IngestionScheduler} calls it). Every attempt is recorded in
 * {@code ingestion_runs}; a failure backs off exponentially, a rate limit
 * waits as long as OpenSky asks, and whatever was stored before a failure
 * stays stored.</p>
 */
@Slf4j
@Service
public class OpenSkyIngestionJob {

    public static final String FEED_STATES = "LIVE_STATES";
    public static final String FEED_FLIGHT_LEGS = "FLIGHT_LEGS";

    /** How often yesterday's legs are looked for again before the day is taken as empty. */
    static final int MAX_PENDING_ATTEMPTS = 12;
    private static final Duration RUN_HISTORY = Duration.ofDays(14);

    private final DataSourcePolicy dataSourcePolicy;
    private final IngestionSettings settings;
    private final OpenSkyClient client;
    private final AirportDirectoryProvider airports;
    private final CallsignMapper callsigns;
    private final LiveStateStore liveStateStore;
    private final FlightLegStore flightLegStore;
    private final IngestionCheckpointRepository checkpoints;
    private final IngestionRunRecorder recorder;
    private final Clock clock;

    private final FeedState statesFeed = new FeedState(FEED_STATES);
    private final FeedState flightLegsFeed = new FeedState(FEED_FLIGHT_LEGS);

    public OpenSkyIngestionJob(DataSourcePolicy dataSourcePolicy, IngestionSettings settings,
                               OpenSkyClient client, AirportDirectoryProvider airports,
                               CallsignMapper callsigns, LiveStateStore liveStateStore,
                               FlightLegStore flightLegStore, IngestionCheckpointRepository checkpoints,
                               IngestionRunRecorder recorder, Clock clock) {
        this.dataSourcePolicy = dataSourcePolicy;
        this.settings = settings;
        this.client = client;
        this.airports = airports;
        this.callsigns = callsigns;
        this.liveStateStore = liveStateStore;
        this.flightLegStore = flightLegStore;
        this.checkpoints = checkpoints;
        this.recorder = recorder;
        this.clock = clock;
    }

    /** True when the data mode and configuration ask for OpenSky ingestion. */
    public boolean active() {
        return settings.enabled() && dataSourcePolicy.mode().ingestsExternalData();
    }

    public boolean flightLegsActive() {
        return active() && settings.flightLegsEnabled();
    }

    public boolean authenticated() {
        return client.authenticated();
    }

    public FeedState statesFeed() {
        return statesFeed;
    }

    public FeedState flightLegsFeed() {
        return flightLegsFeed;
    }

    /** Runs the live states feed when ingestion is active and the feed is due. */
    public synchronized Optional<IngestionRun> pollStatesIfDue() {
        if (!active() || !statesFeed.due(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(pollStates());
    }

    /** Runs the flight-leg feed when it is active and due. */
    public synchronized Optional<IngestionRun> importFlightLegsIfDue() {
        if (!flightLegsActive() || !flightLegsFeed.due(clock.instant())) {
            return Optional.empty();
        }
        return importFlightLegs();
    }

    // ── live states ─────────────────────────────────────────────────────────

    /** One poll of the live states feed, whether or not it is due. */
    public synchronized IngestionRun pollStates() {
        Instant started = clock.instant();
        IngestionCounts counts = new IngestionCounts();
        Duration interval = settings.statesPollInterval();
        try {
            AirportDirectory directory = airports.get();
            if (directory.isEmpty()) {
                statesFeed.succeeded(started, interval, 0, IngestionRun.Status.SKIPPED);
                return finish(FEED_STATES, started, IngestionRun.Status.SKIPPED, counts, 0,
                        "No airports in the network");
            }
            BoundingBox box = settings.boundingBox().isEmpty()
                    ? directory.boundingBox(settings.boundingBoxMargin())
                    : BoundingBox.parse(settings.boundingBox());
            interval = settings.effectiveStatesInterval(box.creditCost(), client.authenticated());
            statesFeed.effectiveInterval(interval);

            OpenSkyStates states = client.fetchStates(box);
            Instant now = clock.instant();
            Instant snapshot = Instant.ofEpochSecond(states.time());
            counts.addFetched(states.states().size() + states.malformed());
            counts.addMalformed(states.malformed());

            OpenSkyNormalizer normalizer =
                    new OpenSkyNormalizer(directory, callsigns, settings.maxStateAge(), settings.vicinityKm());
            List<NormalizedAircraftState> accepted = new ArrayList<>();
            for (OpenSkyStateVector state : states.states()) {
                Normalization<NormalizedAircraftState> result = normalizer.normalize(state, snapshot, now);
                if (result.isAccepted()) {
                    accepted.add(result.value());
                } else {
                    counts.addSkipped(result.rejected());
                }
            }
            liveStateStore.store(accepted, now, counts);
            liveStateStore.prune(now.minus(settings.stateRetention()));
            recorder.prune(now.minus(RUN_HISTORY));

            statesFeed.succeeded(now, interval, counts.inserted() + counts.updated(), IngestionRun.Status.SUCCESS);
            return finish(FEED_STATES, started, IngestionRun.Status.SUCCESS, counts,
                    client.callsLastOperation(), null);
        } catch (RuntimeException ex) {
            // OpenSky failures (rate limit, outage, bad credentials) and storage failures alike.
            return failed(statesFeed, FEED_STATES, started, interval, counts, client.callsLastOperation(), ex);
        }
    }

    // ── flight legs ─────────────────────────────────────────────────────────

    /**
     * One cycle of the flight-leg feed: for each of the past days and each
     * airport that is not yet imported, fetch the departures and store the
     * legs between network airports.
     *
     * @return the recorded run, or empty when there was nothing left to fetch
     */
    public synchronized Optional<IngestionRun> importFlightLegs() {
        Instant started = clock.instant();
        Duration interval = settings.flightLegsInterval();
        IngestionCounts counts = new IngestionCounts();
        int calls = 0;
        int failedBatches = 0;
        boolean budgetReached = false;
        boolean dailyBudgetReached = false;
        try {
            AirportDirectory directory = airports.get();
            OpenSkyNormalizer normalizer =
                    new OpenSkyNormalizer(directory, callsigns, settings.maxStateAge(), settings.vicinityKm());
            LocalDate today = LocalDate.ofInstant(started, ZoneOffset.UTC);
            // Daily credit budget of the /flights bucket, counted from the recorded runs of this UTC day.
            long usedToday = recorder.apiCallsSince(DataSources.OPENSKY, FEED_FLIGHT_LEGS,
                    today.atStartOfDay(ZoneOffset.UTC).toInstant());
            long callsLeftToday = Math.max(0, settings.flightLegCallsPerDay(client.authenticated()) - usedToday);

            days:
            for (int daysAgo = 1; daysAgo <= settings.flightLegsLookbackDays(); daysAgo++) {
                LocalDate day = today.minusDays(daysAgo);
                Instant begin = day.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant end = begin.plus(Duration.ofDays(1)).minusSeconds(1);
                for (AirportRef airport : directory.all()) {
                    if (airport.icao() == null) {
                        continue;
                    }
                    IngestionCheckpoint checkpoint = checkpoints
                            .findByDataSourceAndFeedAndScopeKeyAndPeriodStart(
                                    DataSources.OPENSKY, FEED_FLIGHT_LEGS, airport.icao(), day)
                            .orElse(null);
                    if (checkpoint != null && checkpoint.getStatus() != IngestionCheckpoint.Status.PENDING) {
                        continue;       // already imported, or known to be empty
                    }
                    if (calls >= callsLeftToday) {
                        budgetReached = true;
                        dailyBudgetReached = true;
                        break days;
                    }
                    if (calls >= settings.flightLegsMaxCalls()) {
                        budgetReached = true;
                        break days;
                    }

                    List<OpenSkyFlight> flights;
                    try {
                        flights = client.fetchDepartures(airport.icao(), begin, end);
                    } finally {
                        calls += client.callsLastOperation();
                    }

                    IngestionCounts batch = new IngestionCounts();
                    batch.addFetched(flights.size());
                    IngestionCheckpoint.Status status;
                    int stored = 0;
                    if (flights.isEmpty()) {
                        // OpenSky answers 404/empty until its nightly batch has run.
                        int attempts = checkpoint == null ? 1 : checkpoint.getAttempts() + 1;
                        status = daysAgo == 1 && attempts < MAX_PENDING_ATTEMPTS
                                ? IngestionCheckpoint.Status.PENDING
                                : IngestionCheckpoint.Status.EMPTY;
                    } else {
                        List<NormalizedFlightLeg> legs = new ArrayList<>();
                        Instant now = clock.instant();
                        for (OpenSkyFlight flight : flights) {
                            Normalization<NormalizedFlightLeg> result = normalizer.normalize(flight, now);
                            if (result.isAccepted()) {
                                legs.add(result.value());
                            } else {
                                batch.addSkipped(result.rejected());
                            }
                        }
                        try {
                            flightLegStore.store(legs, now, batch);
                            status = IngestionCheckpoint.Status.COMPLETE;
                            stored = batch.inserted() + batch.updated() + batch.unchanged();
                        } catch (RuntimeException ex) {
                            // The batch was rolled back; earlier batches stay stored. Try it again later.
                            failedBatches++;
                            status = IngestionCheckpoint.Status.PENDING;
                            log.warn("event=ingestion_batch_failed source=OPENSKY feed={} airport={} day={} "
                                            + "error=\"{}\"", FEED_FLIGHT_LEGS, airport.icao(), day,
                                    describe(ex));
                            batch = new IngestionCounts();
                            batch.addFetched(flights.size());
                        }
                    }
                    counts.add(batch);
                    saveCheckpoint(checkpoint, airport.icao(), day, status, stored);
                }
            }
        } catch (RuntimeException ex) {
            // Rate limit, bad credentials or an outage: stop the cycle, keep what was stored.
            return Optional.of(failed(flightLegsFeed, FEED_FLIGHT_LEGS, started, interval, counts, calls, ex));
        }

        boolean complete = failedBatches == 0 && !budgetReached;
        IngestionRun.Status status = complete ? IngestionRun.Status.SUCCESS : IngestionRun.Status.PARTIAL;
        flightLegsFeed.succeeded(clock.instant(), interval, counts.inserted() + counts.updated(), status);
        if (calls == 0) {
            return Optional.empty();    // everything was imported already: no API call, nothing to record
        }
        String note = dailyBudgetReached
                ? "Stopped at the daily credit budget (" + settings.flightLegCallsPerDay(client.authenticated())
                        + " /flights calls per UTC day); the rest follows after 00:00 UTC"
                : budgetReached ? "Stopped at the per-cycle call limit; the rest follows next cycle"
                : failedBatches > 0 ? failedBatches + " batch(es) could not be stored and will be retried" : null;
        return Optional.of(finish(FEED_FLIGHT_LEGS, started, status, counts, calls, note));
    }

    private void saveCheckpoint(IngestionCheckpoint existing, String scopeKey, LocalDate day,
                                IngestionCheckpoint.Status status, int records) {
        IngestionCheckpoint checkpoint = existing != null ? existing : IngestionCheckpoint.builder()
                .dataSource(DataSources.OPENSKY)
                .feed(FEED_FLIGHT_LEGS)
                .scopeKey(scopeKey)
                .periodStart(day)
                .build();
        checkpoint.setStatus(status);
        checkpoint.setAttempts(checkpoint.getAttempts() + 1);
        checkpoint.setRecords(records);
        checkpoint.setUpdatedAt(clock.instant());
        checkpoints.save(checkpoint);
    }

    // ── outcome handling ────────────────────────────────────────────────────

    private IngestionRun failed(FeedState feed, String feedName, Instant started, Duration interval,
                                IngestionCounts counts, int calls, RuntimeException ex) {
        Instant now = clock.instant();
        String error = describe(ex);
        if (ex instanceof OpenSkyException openSky && openSky.kind() == OpenSkyException.Kind.RATE_LIMITED) {
            Duration wait = openSky.retryAfter() != null ? openSky.retryAfter() : interval;
            feed.rateLimited(now, interval, wait, error);
            return finish(feedName, started, IngestionRun.Status.RATE_LIMITED, counts, calls, error);
        }
        feed.failed(now, interval, settings.failureBackoff(), settings.maxFailureBackoff(), error);
        return finish(feedName, started, IngestionRun.Status.FAILED, counts, calls, error);
    }

    private IngestionRun finish(String feed, Instant started, IngestionRun.Status status,
                                IngestionCounts counts, int calls, String message) {
        Instant finished = clock.instant();
        Integer remaining = client.rateLimitRemaining().orElse(null);
        boolean problem = status == IngestionRun.Status.FAILED || status == IngestionRun.Status.RATE_LIMITED;
        String line = "event=ingestion_run source=OPENSKY feed={} status={} fetched={} inserted={} updated={} "
                + "unchanged={} skipped={} api_calls={} duration_ms={} rate_limit_remaining={} message=\"{}\"";
        Object[] values = {feed, status, counts.fetched(), counts.inserted(), counts.updated(),
                counts.unchanged(), counts.skipped(), calls, Duration.between(started, finished).toMillis(),
                remaining == null ? "unknown" : remaining, message == null ? "" : message};
        if (problem) {
            log.warn(line, values);
        } else {
            log.info(line, values);
        }
        return recorder.record(DataSources.OPENSKY, feed, started, finished, status, counts, calls, remaining,
                message);
    }

    /** Exception text for logs and run records, with anything credential-like removed. */
    private static String describe(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return LogRedaction.redact(message);
    }
}
