package com.aerosync.ingestion;

import com.aerosync.model.IngestionRun;
import com.aerosync.repository.IngestionRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Persists the outcome of every ingestion attempt (table {@code ingestion_runs}). */
@Service
public class IngestionRunRecorder {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_DETAILS_LENGTH = 1000;

    private final IngestionRunRepository repository;

    public IngestionRunRecorder(IngestionRunRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IngestionRun record(String dataSource, String feed, Instant startedAt, Instant finishedAt,
                               IngestionRun.Status status, IngestionCounts counts, int apiCalls,
                               Integer rateLimitRemaining, String errorMessage) {
        return repository.save(IngestionRun.builder()
                .dataSource(dataSource)
                .feed(feed)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .status(status)
                .recordsFetched(counts.fetched())
                .recordsInserted(counts.inserted())
                .recordsUpdated(counts.updated())
                .recordsUnchanged(counts.unchanged())
                .recordsSkipped(counts.skipped())
                .apiCalls(apiCalls)
                .rateLimitRemaining(rateLimitRemaining)
                .details(truncate(counts.skipDetails(), MAX_DETAILS_LENGTH))
                .errorMessage(truncate(errorMessage, MAX_ERROR_LENGTH))
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<IngestionRun> latest(String dataSource, String feed) {
        return repository.findFirstByDataSourceAndFeedOrderByStartedAtDesc(dataSource, feed);
    }

    @Transactional(readOnly = true)
    public Optional<IngestionRun> latestSuccess(String dataSource, String feed) {
        return repository.findFirstByDataSourceAndFeedAndStatusOrderByStartedAtDesc(
                dataSource, feed, IngestionRun.Status.SUCCESS);
    }

    /** API calls recorded for a feed since {@code since}; survives restarts because it is read from the run table. */
    @Transactional(readOnly = true)
    public long apiCallsSince(String dataSource, String feed, Instant since) {
        return repository.sumApiCallsSince(dataSource, feed, since);
    }

    /** Deletes run records older than {@code before}. */
    @Transactional
    public int prune(Instant before) {
        return repository.deleteOlderThan(before);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
