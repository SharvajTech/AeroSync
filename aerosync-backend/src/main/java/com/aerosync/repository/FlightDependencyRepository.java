package com.aerosync.repository;

import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Dependency queries fetch both endpoint flights and their schedules (needed
 * for scheduled times during traversal) in one statement.
 */
@Repository
public interface FlightDependencyRepository extends JpaRepository<FlightDependency, UUID> {

    /** Active dependencies with at least one endpoint operating on the given service date. */
    @Query("""
            select dep from FlightDependency dep
            join fetch dep.sourceFlight sf
            join fetch sf.schedule ss
            join fetch dep.targetFlight tf
            join fetch tf.schedule ts
            where dep.active = true
              and (ss.serviceDate = :serviceDate or ts.serviceDate = :serviceDate)
            """)
    List<FlightDependency> findActiveByServiceDate(@Param("serviceDate") LocalDate serviceDate);

    /** Active dependencies with the source or the target in the given set of flights. */
    @Query("""
            select dep from FlightDependency dep
            join fetch dep.sourceFlight sf
            join fetch sf.schedule
            join fetch dep.targetFlight tf
            join fetch tf.schedule
            where dep.active = true
              and (sf.flightId in :flightIds or tf.flightId in :flightIds)
            """)
    List<FlightDependency> findActiveTouching(@Param("flightIds") Collection<UUID> flightIds);

    /** Downstream frontier: active dependencies whose source is in the given set. */
    @Query("""
            select dep from FlightDependency dep
            join fetch dep.sourceFlight sf
            join fetch sf.schedule
            join fetch dep.targetFlight tf
            join fetch tf.schedule
            where dep.active = true
              and sf.flightId in :flightIds
            """)
    List<FlightDependency> findActiveOutgoing(@Param("flightIds") Collection<UUID> flightIds);

    /** Upstream frontier: active dependencies whose target is in the given set. */
    @Query("""
            select dep from FlightDependency dep
            join fetch dep.sourceFlight sf
            join fetch sf.schedule
            join fetch dep.targetFlight tf
            join fetch tf.schedule
            where dep.active = true
              and tf.flightId in :flightIds
            """)
    List<FlightDependency> findActiveIncoming(@Param("flightIds") Collection<UUID> flightIds);

    boolean existsBySourceFlight_FlightIdAndTargetFlight_FlightIdAndDependencyType(
            UUID sourceFlightId, UUID targetFlightId, DependencyType dependencyType);
}
