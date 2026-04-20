package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "flights")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String flightId;          // e.g. AI-202

    private String origin;            // BOM
    private String destination;       // DEL
    private String aircraftType;      // A320
    private Integer aircraftAge;

    private LocalDateTime scheduledDeparture;
    private LocalDateTime actualDeparture;

    private Integer departureHour;
    private Integer dayOfWeek;
    private Integer month;
    private Double  routeDistanceKm;
    private Integer weatherScore;
    private Double  crewDutyHours;
    private Double  historicalDelayRate;
    private Integer gateTurnaroundMins;

    // ML prediction results (filled after /predict call)
    private Double  delayProbability;
    private String  riskLevel;        // LOW / MEDIUM / HIGH
    private Boolean predictedDelayed;

    // Current operational status
    @Enumerated(EnumType.STRING)
    private FlightStatus status;

    private String assignedGate;
    private String assignedCrewId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum FlightStatus {
        SCHEDULED, DELAYED, CANCELLED, DEPARTED, LANDED, DISRUPTED
    }
}