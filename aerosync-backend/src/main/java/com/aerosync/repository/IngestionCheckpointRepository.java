package com.aerosync.repository;

import com.aerosync.model.IngestionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionCheckpointRepository extends JpaRepository<IngestionCheckpoint, UUID> {

    Optional<IngestionCheckpoint> findByDataSourceAndFeedAndScopeKeyAndPeriodStart(
            String dataSource, String feed, String scopeKey, LocalDate periodStart);
}
