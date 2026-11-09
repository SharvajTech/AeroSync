package com.aerosync.repository;

import com.aerosync.model.FlightSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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
}
