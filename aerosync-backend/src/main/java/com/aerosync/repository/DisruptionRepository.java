package com.aerosync.repository;

import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DisruptionRepository extends JpaRepository<Disruption, Long> {
    List<Disruption> findByStatus(DisruptionStatus status);
    List<Disruption> findByFlightId(String flightId);

    // ── Airport-scoped queries (Stage 3) ─────────────────────────────────────
    List<Disruption> findByFlightIdIn(Collection<String> flightIds);

    List<Disruption> findByStatusAndFlightIdIn(DisruptionStatus status, Collection<String> flightIds);

    // ── Disruption monitoring (Stage 4) ──────────────────────────────────────

    /** Open disruptions of operational-network flights (graph highlighting). */
    List<Disruption> findByFlightInstanceIdInAndStatusIn(Collection<UUID> flightInstanceIds,
                                                         Collection<DisruptionStatus> statuses);

    List<Disruption> findByStatusIn(Collection<DisruptionStatus> statuses);

    /** Open disruptions the monitor raised, to refresh or close them. */
    List<Disruption> findByDetectionSourceAndStatusIn(String detectionSource, Collection<DisruptionStatus> statuses);

    /** Whether the same event was closed recently (the monitor then waits before raising it again). */
    boolean existsByDetectionKeyAndResolvedAtAfter(String detectionKey, LocalDateTime resolvedAfter);

    long countByDetectionSourceAndStatusIn(String detectionSource, Collection<DisruptionStatus> statuses);
}