package com.aerosync.repository;

import com.aerosync.model.Gate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GateRepository extends JpaRepository<Gate, Long> {
    List<Gate> findByAvailableTrue();
    List<Gate> findByTerminal(String terminal);
}