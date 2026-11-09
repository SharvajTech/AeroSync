package com.aerosync.repository;

import com.aerosync.model.Aircraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AircraftRepository extends JpaRepository<Aircraft, UUID> {

    Optional<Aircraft> findByRegistration(String registration);
}
