package com.aerosync.repository;

import com.aerosync.model.IngestionRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

    /** The most recent run of a feed, whatever its outcome. */
    Optional<IngestionRun> findFirstByDataSourceAndFeedOrderByStartedAtDesc(String dataSource, String feed);

    /** The most recent run of a feed with the given outcome. */
    Optional<IngestionRun> findFirstByDataSourceAndFeedAndStatusOrderByStartedAtDesc(
            String dataSource, String feed, IngestionRun.Status status);

    /** API calls a feed made since the given time (used for the daily credit budget). */
    @Query("select coalesce(sum(r.apiCalls), 0) from IngestionRun r "
            + "where r.dataSource = :dataSource and r.feed = :feed and r.startedAt >= :since")
    long sumApiCallsSince(@Param("dataSource") String dataSource, @Param("feed") String feed,
                          @Param("since") Instant since);

    @Transactional
    @Modifying
    @Query("delete from IngestionRun r where r.startedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
