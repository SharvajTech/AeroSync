package com.aerosync.repository;

import com.aerosync.model.Flight;
import com.aerosync.model.Flight.FlightStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Long> {
    Optional<Flight> findByFlightId(String flightId);
    List<Flight> findByStatus(FlightStatus status);
    List<Flight> findByRiskLevel(String riskLevel);
    List<Flight> findByOriginAndDestination(String origin, String destination);
}