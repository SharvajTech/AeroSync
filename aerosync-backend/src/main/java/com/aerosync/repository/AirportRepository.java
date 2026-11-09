package com.aerosync.repository;

import com.aerosync.model.Airport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AirportRepository extends JpaRepository<Airport, UUID> {

    Optional<Airport> findByIataCode(String iataCode);

    List<Airport> findAllByOrderByIataCodeAsc();
}
