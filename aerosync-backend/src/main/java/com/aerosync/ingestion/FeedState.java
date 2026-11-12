package com.aerosync.ingestion;

import com.aerosync.model.IngestionRun;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory state of one ingestion feed since the backend started: when it
 * may run next (backoff after failures, the wait a rate limit asks for) and
 * simple counters for the status endpoint. Durable history is in
 * {@code ingestion_runs}.
 */
public final class FeedState {

    private final String feed;
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong recordsStored = new AtomicLong();

    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessAt;
    private volatile IngestionRun.Status lastStatus;
    private volatile String lastError;
    private volatile int consecutiveFailures;
    private volatile Duration effectiveInterval;

    public FeedState(String feed) {
        this.feed = feed;
    }

    public String feed() {
        return feed;
    }

    public boolean due(Instant now) {
        return !now.isBefore(nextAttemptAt);
    }

    /** A cycle finished normally; the feed runs again after {@code interval}. */
    public void succeeded(Instant now, Duration interval, int stored, IngestionRun.Status status) {
        runs.incrementAndGet();
        successes.incrementAndGet();
        recordsStored.addAndGet(stored);
        consecutiveFailures = 0;
        lastError = null;
        lastStatus = status;
        lastAttemptAt = now;
        lastSuccessAt = now;
        nextAttemptAt = now.plus(interval);
    }

    /**
     * A cycle failed; the wait doubles with every consecutive failure
     * ({@code base}, 2×, 4×, ...) up to {@code max}, and never undercuts the
     * normal interval.
     */
    public void failed(Instant now, Duration interval, Duration base, Duration max, String error) {
        runs.incrementAndGet();
        failures.incrementAndGet();
        consecutiveFailures++;
        lastError = error;
        lastStatus = IngestionRun.Status.FAILED;
        lastAttemptAt = now;
        Duration backoff = base.multipliedBy(1L << Math.min(consecutiveFailures - 1, 16));
        if (backoff.compareTo(max) > 0) {
            backoff = max;
        }
        nextAttemptAt = now.plus(backoff.compareTo(interval) > 0 ? backoff : interval);
    }

    /** The source asked us to wait; nothing is attempted before {@code retryAfter} has passed. */
    public void rateLimited(Instant now, Duration interval, Duration retryAfter, String error) {
        runs.incrementAndGet();
        rateLimited.incrementAndGet();
        lastError = error;
        lastStatus = IngestionRun.Status.RATE_LIMITED;
        lastAttemptAt = now;
        nextAttemptAt = now.plus(retryAfter.compareTo(interval) > 0 ? retryAfter : interval);
    }

    public void effectiveInterval(Duration interval) {
        this.effectiveInterval = interval;
    }

    public Duration effectiveInterval() {
        return effectiveInterval;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public IngestionRun.Status lastStatus() {
        return lastStatus;
    }

    public String lastError() {
        return lastError;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public long runs() {
        return runs.get();
    }

    public long successes() {
        return successes.get();
    }

    public long failures() {
        return failures.get();
    }

    public long rateLimitedRuns() {
        return rateLimited.get();
    }

    public long recordsStored() {
        return recordsStored.get();
    }
}
