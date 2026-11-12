package com.aerosync.repository;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, UUID> {

    boolean existsByFlightNumberAndServiceDateAndOrigin_AirportId(
            String flightNumber, LocalDate serviceDate, UUID originAirportId);

    /** Earliest scheduled leg (used to report the available service-date window). */
    Optional<FlightSchedule> findFirstByOrderByServiceDateAsc();

    /** Latest scheduled leg (used to report the available service-date window). */
    Optional<FlightSchedule> findFirstByOrderByServiceDateDesc();

    // ── Stage 4: data mode and ingestion ─────────────────────────────────────

    /** Earliest schedule among the given origins (the data window of a data mode). */
    Optional<FlightSchedule> findFirstByDataOriginInOrderByServiceDateAsc(Collection<DataOrigin> origins);

    Optional<FlightSchedule> findFirstByDataOriginInOrderByServiceDateDesc(Collection<DataOrigin> origins);

    /** The row ingestion wrote for an external record, if any. */
    Optional<FlightSchedule> findByDataSourceAndSourceRecordId(String dataSource, String sourceRecordId);

    /** The leg with this key (flight number, local departure date, origin airport), if any. */
    Optional<FlightSchedule> findByFlightNumberAndServiceDateAndOrigin_AirportId(
            String flightNumber, LocalDate serviceDate, UUID originAirportId);

    long countByDataSource(String dataSource);
}
