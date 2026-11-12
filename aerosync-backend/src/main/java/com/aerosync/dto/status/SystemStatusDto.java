package com.aerosync.dto.status;

import com.aerosync.datasource.DataSourceMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Health and freshness of the data pipeline, for the dashboard's status bar.
 * It describes the pipeline (modes, timestamps, counters), not flights, so
 * it is the same for every signed-in user and holds no airport's data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemStatusDto(
        Instant generatedAt,
        DataSourceMode dataMode,
        String dataModeDescription,
        ComponentDto database,
        ComponentDto mlService,
        List<FeedDto> feeds,
        MonitoringDto monitoring) {

    /** A dependency of the backend. {@code status} is UP or DOWN. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComponentDto(String status, Instant checkedAt, String detail) {
    }

    /**
     * One external data feed.
     *
     * @param status LIVE (delivering), STALE (last success older than expected),
     *               FAILING, RATE_LIMITED, WAITING (enabled, nothing received
     *               yet), DISABLED (off in this data mode or by configuration)
     *               or UNCONFIGURED (no API key)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedDto(
            String feed,
            String dataSource,
            String description,
            String status,
            boolean enabled,
            Boolean authenticated,
            Instant lastSuccessAt,
            Instant lastAttemptAt,
            String lastError,
            Long intervalSeconds,
            Instant nextAttemptAt,
            Integer consecutiveFailures,
            Integer rateLimitRemaining,
            FeedRunDto lastRun,
            FeedCountersDto sinceStart) {
    }

    /** What the most recent recorded run of a feed did. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedRunDto(
            String status,
            Instant startedAt,
            Instant finishedAt,
            int recordsFetched,
            int recordsInserted,
            int recordsUpdated,
            int recordsUnchanged,
            int recordsSkipped,
            int apiCalls,
            String skipDetails) {
    }

    /** Counters since the backend started. */
    public record FeedCountersDto(long runs, long successes, long failures, long rateLimited, long recordsStored) {
    }

    /**
     * The disruption monitor.
     *
     * @param status RUNNING, WAITING (enabled, first cycle not run yet), FAILING or DISABLED
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MonitoringDto(
            String status,
            boolean enabled,
            Instant lastRunAt,
            Long intervalSeconds,
            Integer detected,
            Integer opened,
            Integer refreshed,
            Integer suppressed,
            Integer cleared,
            String lastError) {
    }
}
