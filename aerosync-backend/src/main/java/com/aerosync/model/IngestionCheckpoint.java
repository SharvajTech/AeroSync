package com.aerosync.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Progress of a batch feed (table {@code ingestion_checkpoints}): one row per
 * source, feed, scope (for example an airport) and day. A completed period is
 * never fetched again, so a restart or a partial failure costs no API calls
 * for what is already imported.
 */
@Entity
@Table(name = "ingestion_checkpoints", uniqueConstraints = @UniqueConstraint(
        name = "uk_ingestion_checkpoints_period",
        columnNames = {"data_source", "feed", "scope_key", "period_start"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionCheckpoint {

    public enum Status {
        /** Imported; do not fetch again. */
        COMPLETE,
        /** The source has nothing for the period; do not fetch again. */
        EMPTY,
        /** Not available yet or failed; fetch again later. */
        PENDING
    }

    @Id
    @Column(name = "checkpoint_id", nullable = false, updatable = false)
    private UUID checkpointId;

    @Column(name = "data_source", nullable = false, length = 40)
    private String dataSource;

    @Column(name = "feed", nullable = false, length = 40)
    private String feed;

    @Column(name = "scope_key", nullable = false, length = 40)
    private String scopeKey;

    /** The UTC day the checkpoint covers. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "records", nullable = false)
    private int records;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (checkpointId == null) {
            checkpointId = UUID.randomUUID();
        }
    }
}
