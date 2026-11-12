package com.aerosync.status;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.datasource.DataSources;
import com.aerosync.dto.status.SystemStatusDto;
import com.aerosync.dto.status.SystemStatusDto.ComponentDto;
import com.aerosync.dto.status.SystemStatusDto.FeedCountersDto;
import com.aerosync.dto.status.SystemStatusDto.FeedDto;
import com.aerosync.dto.status.SystemStatusDto.FeedRunDto;
import com.aerosync.dto.status.SystemStatusDto.MonitoringDto;
import com.aerosync.ingestion.FeedState;
import com.aerosync.ingestion.IngestionRunRecorder;
import com.aerosync.ingestion.IngestionSettings;
import com.aerosync.ingestion.OpenSkyIngestionJob;
import com.aerosync.model.IngestionRun;
import com.aerosync.monitoring.BackgroundJobs;
import com.aerosync.monitoring.DisruptionMonitorService;
import com.aerosync.monitoring.MonitorCycleResult;
import com.aerosync.service.WeatherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the pipeline status: data mode, dependencies, each external feed
 * with its last success and freshness, and the disruption monitor. Reads
 * recorded state only; it never calls an external API.
 */
@Service
public class SystemStatusService {

    public static final String LIVE = "LIVE";
    public static final String STALE = "STALE";
    public static final String FAILING = "FAILING";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String WAITING = "WAITING";
    public static final String DISABLED = "DISABLED";
    public static final String UNCONFIGURED = "UNCONFIGURED";

    /** A feed is stale when its last success is older than this many intervals (plus a minute). */
    static final int STALE_AFTER_INTERVALS = 2;

    private final DataSourcePolicy dataSourcePolicy;
    private final DependencyProbe probe;
    private final OpenSkyIngestionJob openSky;
    private final IngestionSettings ingestionSettings;
    private final IngestionRunRecorder runs;
    private final WeatherService weatherService;
    private final DisruptionMonitorService monitor;
    private final BackgroundJobs backgroundJobs;
    private final Clock clock;
    private final Duration monitorInterval;

    public SystemStatusService(DataSourcePolicy dataSourcePolicy, DependencyProbe probe,
                               OpenSkyIngestionJob openSky, IngestionSettings ingestionSettings,
                               IngestionRunRecorder runs, WeatherService weatherService,
                               DisruptionMonitorService monitor, BackgroundJobs backgroundJobs, Clock clock,
                               @Value("${aerosync.monitoring.interval-ms:120000}") long monitorIntervalMs) {
        this.dataSourcePolicy = dataSourcePolicy;
        this.probe = probe;
        this.openSky = openSky;
        this.ingestionSettings = ingestionSettings;
        this.runs = runs;
        this.weatherService = weatherService;
        this.monitor = monitor;
        this.backgroundJobs = backgroundJobs;
        this.clock = clock;
        this.monitorInterval = Duration.ofMillis(monitorIntervalMs);
    }

    public SystemStatusDto status() {
        Instant now = clock.instant();
        DataSourceMode mode = dataSourcePolicy.mode();
        return new SystemStatusDto(
                now,
                mode,
                describe(mode),
                component(probe.database()),
                component(probe.mlService()),
                List.of(
                        openSkyFeed(OpenSkyIngestionJob.FEED_STATES, openSky.statesFeed(), openSky.active(),
                                "Live aircraft positions near the network's airports", now,
                                ingestionSettings.statesPollInterval()),
                        openSkyFeed(OpenSkyIngestionJob.FEED_FLIGHT_LEGS, openSky.flightLegsFeed(),
                                openSky.flightLegsActive(),
                                "Flights of previous days (OpenSky derives them in a nightly batch)", now,
                                ingestionSettings.flightLegsInterval()),
                        weatherFeed(now)),
                monitoring(now));
    }

    static String describe(DataSourceMode mode) {
        return switch (mode) {
            case SYNTHETIC -> "Generated demonstration data only. No external flight data is fetched.";
            case LIVE -> "External data only. Nothing generated is shown.";
            case HYBRID -> "External data together with generated demonstration data; every record is labelled.";
        };
    }

    private static ComponentDto component(DependencyProbe.Check check) {
        return new ComponentDto(check.up() ? "UP" : "DOWN", check.checkedAt(), check.detail());
    }

    private FeedDto openSkyFeed(String feed, FeedState state, boolean active, String description, Instant now,
                                Duration configuredInterval) {
        Optional<IngestionRun> latest = runs.latest(DataSources.OPENSKY, feed);
        Instant lastSuccess = state.lastSuccessAt() != null ? state.lastSuccessAt()
                : runs.latestSuccess(DataSources.OPENSKY, feed).map(IngestionRun::getFinishedAt).orElse(null);
        Duration interval = state.effectiveInterval() != null ? state.effectiveInterval() : configuredInterval;

        String status;
        if (!active) {
            status = DISABLED;
        } else if (state.lastStatus() == IngestionRun.Status.RATE_LIMITED) {
            status = RATE_LIMITED;
        } else if (state.consecutiveFailures() > 0) {
            status = FAILING;
        } else if (lastSuccess == null) {
            status = WAITING;
        } else {
            status = isStale(lastSuccess, interval, now) ? STALE : LIVE;
        }
        String error = active ? state.lastError() : disabledReason();
        return new FeedDto(feed, DataSources.OPENSKY, description, status, active, openSky.authenticated(),
                lastSuccess, state.lastAttemptAt(), error, interval.getSeconds(),
                active && state.lastAttemptAt() != null ? state.nextAttemptAt() : null,
                state.consecutiveFailures(),
                latest.map(IngestionRun::getRateLimitRemaining).orElse(null),
                latest.map(SystemStatusService::run).orElse(null),
                new FeedCountersDto(state.runs(), state.successes(), state.failures(), state.rateLimitedRuns(),
                        state.recordsStored()));
    }

    private String disabledReason() {
        if (!dataSourcePolicy.mode().ingestsExternalData()) {
            return "Not fetched in data mode " + dataSourcePolicy.mode();
        }
        return "Switched off by configuration";
    }

    private FeedDto weatherFeed(Instant now) {
        WeatherService.FeedStatus weather = weatherService.status();
        String status;
        if (!weather.configured()) {
            status = UNCONFIGURED;
        } else if (weather.lastError() != null) {
            status = FAILING;
        } else if (weather.lastSuccessAt() == null) {
            status = WAITING;
        } else {
            status = isStale(weather.lastSuccessAt(), weather.refreshInterval(), now) ? STALE : LIVE;
        }
        return new FeedDto("WEATHER", DataSources.OPENWEATHERMAP,
                "Observed weather and forecasts at the network's airports", status, weather.configured(), null,
                weather.lastSuccessAt(), weather.lastAttemptAt(),
                weather.configured() ? weather.lastError() : "No API key configured (WEATHER_API_KEY)",
                weather.refreshInterval().getSeconds(), null, null, null, null, null);
    }

    private MonitoringDto monitoring(Instant now) {
        boolean enabled = backgroundJobs.monitoringEnabled();
        MonitorCycleResult last = monitor.lastResult();
        if (last == null) {
            return new MonitoringDto(enabled ? WAITING : DISABLED, enabled, null, monitorInterval.getSeconds(),
                    null, null, null, null, null, null);
        }
        String status = !enabled ? DISABLED : last.error() != null ? FAILING
                : isStale(last.ranAt(), monitorInterval, now) ? STALE : "RUNNING";
        return new MonitoringDto(status, enabled, last.ranAt(), monitorInterval.getSeconds(), last.detected(),
                last.opened(), last.refreshed(), last.suppressed(), last.cleared(), last.error());
    }

    /** True when the last success is older than two intervals plus a minute. */
    public static boolean isStale(Instant lastSuccess, Duration interval, Instant now) {
        return lastSuccess.plus(interval.multipliedBy(STALE_AFTER_INTERVALS)).plusSeconds(60).isBefore(now);
    }

    private static FeedRunDto run(IngestionRun run) {
        return new FeedRunDto(run.getStatus().name(), run.getStartedAt(), run.getFinishedAt(),
                run.getRecordsFetched(), run.getRecordsInserted(), run.getRecordsUpdated(),
                run.getRecordsUnchanged(), run.getRecordsSkipped(), run.getApiCalls(), run.getDetails());
    }
}
