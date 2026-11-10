package com.aerosync.repository;

import com.aerosync.model.FlightInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All graph queries fetch the schedule, both airports and the aircraft in the
 * same SQL statement, so building a graph never triggers lazy loading (N+1).
 */
@Repository
public interface FlightInstanceRepository extends JpaRepository<FlightInstance, UUID> {

    @Query("""
            select fi from FlightInstance fi
            join fetch fi.schedule s
            join fetch s.origin
            join fetch s.destination
            left join fetch fi.aircraft
            left join fetch fi.assignedGate
            where s.serviceDate = :serviceDate
            """)
    List<FlightInstance> findGraphFlightsByServiceDate(@Param("serviceDate") LocalDate serviceDate);

    /** Flights of the service date departing from or arriving at one of the airports (airport-scoped network). */
    @Query("""
            select fi from FlightInstance fi
            join fetch fi.schedule s
            join fetch s.origin o
            join fetch s.destination dest
            left join fetch fi.aircraft
            left join fetch fi.assignedGate
            where s.serviceDate = :serviceDate
              and (o.iataCode in :iataCodes or dest.iataCode in :iataCodes)
            """)
    List<FlightInstance> findGraphFlightsByServiceDateTouchingAirports(@Param("serviceDate") LocalDate serviceDate,
                                                                      @Param("iataCodes") Collection<String> iataCodes);

    /** Departures from, and arrivals at, one airport within [windowStart, windowEnd). */
    @Query("""
            select fi from FlightInstance fi
            join fetch fi.schedule s
            join fetch s.origin o
            join fetch s.destination dest
            left join fetch fi.aircraft
            left join fetch fi.assignedGate
            where (o.iataCode = :iataCode
                   and s.scheduledDeparture >= :windowStart and s.scheduledDeparture < :windowEnd)
               or (dest.iataCode = :iataCode
                   and s.scheduledArrival >= :windowStart and s.scheduledArrival < :windowEnd)
            """)
    List<FlightInstance> findAirportMovements(@Param("iataCode") String iataCode,
                                              @Param("windowStart") Instant windowStart,
                                              @Param("windowEnd") Instant windowEnd);

    @Query("""
            select fi from FlightInstance fi
            join fetch fi.schedule s
            join fetch s.origin
            join fetch s.destination
            left join fetch fi.aircraft
            left join fetch fi.assignedGate
            where fi.flightId in :flightIds
            """)
    List<FlightInstance> findGraphFlightsByIds(@Param("flightIds") Collection<UUID> flightIds);

    @Query("""
            select fi from FlightInstance fi
            join fetch fi.schedule s
            join fetch s.origin
            join fetch s.destination
            left join fetch fi.aircraft
            left join fetch fi.assignedGate
            where fi.flightId = :flightId
            """)
    Optional<FlightInstance> findGraphFlightById(@Param("flightId") UUID flightId);

    boolean existsBySchedule_ScheduleId(UUID scheduleId);
}
