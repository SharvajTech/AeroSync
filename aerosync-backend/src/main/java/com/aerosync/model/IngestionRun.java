package com.aerosync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One ingestion attempt (table {@code ingestion_runs}): what was fetched from
 * an external source and what it changed. Never holds credentials or tokens.
 */
@Entity
@Table(name = "ingestion_runs", indexes =
        @Index(name = "idx_ingestion_runs_feed", columnList = "data_source, feed, started_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionRun {

    public enum Status {
        RUNNING,
        /** Everything fetched was handled. */
        SUCCESS,
        /** Part of the work succeeded; the rest will be retried. */
        PARTIAL,
        FAILED,
        /** The source's credit budget is used up; ingestion waits. */
        RATE_LIMITED,
        SKIPPED
    }

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "data_source", nullable = false, length = 40)
    private String dataSource;

    /** Which feed of the source, for example LIVE_STATES or FLIGHT_LEGS. */
    @Column(name = "feed", nullable = false, length = 40)
    private String feed;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "records_fetched", nullable = false)
    private int recordsFetched;

    @Column(name = "records_inserted", nullable = false)
    private int recordsInserted;

    @Column(name = "records_updated", nullable = false)
    private int recordsUpdated;

    @Column(name = "records_unchanged", nullable = false)
    private int recordsUnchanged;

    @Column(name = "records_skipped", nullable = false)
    private int recordsSkipped;

    @Column(name = "api_calls", nullable = false)
    private int apiCalls;

    @Column(name = "rate_limit_remaining")
    private Integer rateLimitRemaining;

    /** Why records were skipped, as {@code REASON=count} pairs. */
    @Column(name = "details", length = 1000)
    private String details;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (runId == null) {
            runId = UUID.randomUUID();
        }
    }
}
