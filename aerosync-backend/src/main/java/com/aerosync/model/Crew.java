package com.aerosync.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "crew")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String  crewId;         // e.g. CR-044
    private String  name;
    private String  role;           // PILOT / CO_PILOT / CABIN_CREW
    private String  certifications; // A320,B737 (comma separated)
    private Double  dutyHoursUsed;
    private Double  maxDutyHours;   // 12.0 per DGCA rules
    private Boolean available;
    private String  currentFlightId;
}