package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Gate (table {@code gates}). Extended in Stage 1 with the owning airport and
 * an operational status (Flyway V1 adds both columns in place; existing rows
 * keep {@code airport_id = NULL} and get {@code status = OPEN}).
 *
 * <p>Column naming kept from the original table so existing code and data keep
 * working: {@code id} is the gate id and {@code compatible_types} holds the
 * supported aircraft types (comma separated).</p>
 */
@Entity
@Table(name = "gates",
        uniqueConstraints = @UniqueConstraint(name = "uk_gates_airport_terminal_gate",
                columnNames = {"airport_id", "terminal", "gate_number"}))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Gate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String gateNumber;     // e.g. B3, C7
    private String terminal;       // T1, T2
    private String compatibleTypes; // A320,B737 (comma separated)
    private Boolean available;
    private String currentFlightId;

    /** Owning airport (FK to airports.airport_id). NULL for gates created before Stage 1. */
    @Column(name = "airport_id")
    private UUID airportId;

    /** Operational status; defaults to OPEN. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private GateStatus status;

    @PrePersist
    void applyDefaults() {
        if (status == null) {
            status = GateStatus.OPEN;
        }
    }
}
