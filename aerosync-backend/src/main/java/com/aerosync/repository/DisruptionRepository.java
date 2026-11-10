package com.aerosync.repository;

import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface DisruptionRepository extends JpaRepository<Disruption, Long> {
    List<Disruption> findByStatus(DisruptionStatus status);
    List<Disruption> findByFlightId(String flightId);

    // ── Airport-scoped queries (Stage 3) ─────────────────────────────────────
    List<Disruption> findByFlightIdIn(Collection<String> flightIds);

    List<Disruption> findByStatusAndFlightIdIn(DisruptionStatus status, Collection<String> flightIds);
}