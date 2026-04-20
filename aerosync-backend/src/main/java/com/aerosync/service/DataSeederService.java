package com.aerosync.service;

import com.aerosync.model.*;
import com.aerosync.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSeederService implements CommandLineRunner {

    private final FlightRepository     flightRepository;
    private final GateRepository       gateRepository;
    private final CrewRepository       crewRepository;

    @Override
    public void run(String... args) {
        seedGates();
        seedCrew();
        seedFlights();
        log.info("✅ Airport data seeded successfully");
    }

    private void seedGates() {
        if (gateRepository.count() > 0) return;
        log.info("Seeding gates...");

        List<Gate> gates = List.of(
                Gate.builder().gateNumber("A1").terminal("T1")
                        .compatibleTypes("A320,B737,A321")
                        .available(true).build(),
                Gate.builder().gateNumber("A2").terminal("T1")
                        .compatibleTypes("A320,B737")
                        .available(true).build(),
                Gate.builder().gateNumber("B1").terminal("T2")
                        .compatibleTypes("B777,A321")
                        .available(true).build(),
                Gate.builder().gateNumber("B2").terminal("T2")
                        .compatibleTypes("A320,B737,ATR72")
                        .available(true).build(),
                Gate.builder().gateNumber("B3").terminal("T2")
                        .compatibleTypes("A320,A321")
                        .available(true).build(),
                Gate.builder().gateNumber("C1").terminal("T3")
                        .compatibleTypes("B777,A321,B737")
                        .available(true).build(),
                Gate.builder().gateNumber("C2").terminal("T3")
                        .compatibleTypes("ATR72,A320")
                        .available(true).build()
        );
        gateRepository.saveAll(gates);
        log.info("  {} gates seeded", gates.size());
    }

    private void seedCrew() {
        if (crewRepository.count() > 0) return;
        log.info("Seeding crew...");

        List<Crew> crew = List.of(
                Crew.builder().crewId("CR-001").name("Capt. Sharma")
                        .role("PILOT").certifications("A320,B737")
                        .dutyHoursUsed(4.0).maxDutyHours(12.0)
                        .available(true).build(),
                Crew.builder().crewId("CR-002").name("Capt. Verma")
                        .role("PILOT").certifications("B777,A321")
                        .dutyHoursUsed(11.5).maxDutyHours(12.0)
                        .available(true).build(),
                Crew.builder().crewId("CR-003").name("FO Patel")
                        .role("CO_PILOT").certifications("A320,B737,A321")
                        .dutyHoursUsed(3.0).maxDutyHours(12.0)
                        .available(true).build(),
                Crew.builder().crewId("CR-004").name("FO Singh")
                        .role("CO_PILOT").certifications("A320")
                        .dutyHoursUsed(9.5).maxDutyHours(12.0)
                        .available(true).build(),
                Crew.builder().crewId("CR-005").name("Capt. Mehta")
                        .role("PILOT").certifications("A320,B737,B777")
                        .dutyHoursUsed(2.0).maxDutyHours(12.0)
                        .available(true).build(),
                Crew.builder().crewId("CR-006").name("FO Reddy")
                        .role("CO_PILOT").certifications("ATR72,A320")
                        .dutyHoursUsed(6.0).maxDutyHours(12.0)
                        .available(true).build()
        );
        crewRepository.saveAll(crew);
        log.info("  {} crew members seeded", crew.size());
    }

    private void seedFlights() {
        if (flightRepository.count() > 0) return;
        log.info("Seeding flights...");

        LocalDateTime now = LocalDateTime.now();

        List<Flight> flights = List.of(
                Flight.builder()
                        .flightId("AI-202").origin("BOM").destination("DEL")
                        .aircraftType("A320").aircraftAge(12)
                        .scheduledDeparture(now.plusHours(2))
                        .departureHour(18).dayOfWeek(4).month(7)
                        .routeDistanceKm(1148.0).weatherScore(2)
                        .crewDutyHours(11.5).historicalDelayRate(0.38)
                        .gateTurnaroundMins(25).assignedGate("B3")
                        .assignedCrewId("CR-002")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                Flight.builder()
                        .flightId("6E-101").origin("PNQ").destination("BOM")
                        .aircraftType("A320").aircraftAge(4)
                        .scheduledDeparture(now.plusHours(1))
                        .departureHour(10).dayOfWeek(1).month(2)
                        .routeDistanceKm(120.0).weatherScore(0)
                        .crewDutyHours(3.0).historicalDelayRate(0.14)
                        .gateTurnaroundMins(60).assignedGate("A1")
                        .assignedCrewId("CR-001")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                Flight.builder()
                        .flightId("AI-505").origin("DEL").destination("BLR")
                        .aircraftType("B777").aircraftAge(8)
                        .scheduledDeparture(now.plusHours(3))
                        .departureHour(20).dayOfWeek(5).month(8)
                        .routeDistanceKm(1740.0).weatherScore(3)
                        .crewDutyHours(10.5).historicalDelayRate(0.42)
                        .gateTurnaroundMins(30).assignedGate("C1")
                        .assignedCrewId("CR-005")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                Flight.builder()
                        .flightId("SG-301").origin("BLR").destination("HYD")
                        .aircraftType("ATR72").aircraftAge(6)
                        .scheduledDeparture(now.plusHours(1))
                        .departureHour(9).dayOfWeek(2).month(4)
                        .routeDistanceKm(500.0).weatherScore(1)
                        .crewDutyHours(5.0).historicalDelayRate(0.18)
                        .gateTurnaroundMins(45).assignedGate("C2")
                        .assignedCrewId("CR-003")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                Flight.builder()
                        .flightId("AI-780").origin("CCU").destination("DEL")
                        .aircraftType("B737").aircraftAge(15)
                        .scheduledDeparture(now.plusHours(4))
                        .departureHour(7).dayOfWeek(0).month(9)
                        .routeDistanceKm(1305.0).weatherScore(2)
                        .crewDutyHours(9.0).historicalDelayRate(0.45)
                        .gateTurnaroundMins(28).assignedGate("A2")
                        .assignedCrewId("CR-004")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build()
        );

        flightRepository.saveAll(flights);
        log.info("  {} flights seeded", flights.size());
    }
}