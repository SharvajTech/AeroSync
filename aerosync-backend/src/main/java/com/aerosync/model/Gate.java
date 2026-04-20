package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "gates")
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
}