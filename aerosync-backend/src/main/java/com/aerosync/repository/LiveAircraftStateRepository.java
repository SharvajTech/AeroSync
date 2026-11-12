package com.aerosync.repository;

import com.aerosync.model.LiveAircraftState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface LiveAircraftStateRepository extends JpaRepository<LiveAircraftState, String> {

    /** Aircraft at or near an airport that were heard from since {@code since}, newest first. */
    List<LiveAircraftState> findByNearestAirportIdAndLastContactAfterOrderByLastContactDesc(
            UUID nearestAirportId, Instant since);

    long countByLastContactAfter(Instant since);

    /** Removes states that are too old to be of use. */
    @Transactional
    @Modifying
    @Query("delete from LiveAircraftState s where s.lastContact < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
