package com.aerosync.repository;

import com.aerosync.model.Crew;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CrewRepository extends JpaRepository<Crew, Long> {
    List<Crew> findByAvailableTrue();
    List<Crew> findByRole(String role);
}