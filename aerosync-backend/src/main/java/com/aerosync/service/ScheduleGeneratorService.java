package com.aerosync.service;

import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleGeneratorService {

    private final FlightRepository flightRepository;

    // Generates realistic daily schedule for 4 airports
    // Each airport has 2-3 flights per day
    // Flights are connected — BOM→DEL aircraft returns as DEL→BOM
    // This creates natural cascade chains for demo
    public void generateDailySchedule() {
        flightRepository.deleteAll();
        log.info("Generating fresh daily schedule...");

        LocalDateTime base  = LocalDate.now().atStartOfDay();
        int dow   = base.getDayOfWeek().getValue() % 7;
        int month = base.getMonthValue();
        LocalDateTime now   = LocalDateTime.now();

        List<Flight> schedule = List.of(

                // ── BOM (Mumbai) — 3 departures ──────────────────────
                // AI-101: BOM→DEL morning — HIGH risk (peak+monsoon)
                Flight.builder()
                        .flightId("AI-101")
                        .origin("BOM").destination("DEL")
                        .aircraftType("A320").aircraftAge(8)
                        .scheduledDeparture(base.plusHours(7))
                        .departureHour(7).dayOfWeek(dow).month(month)
                        .routeDistanceKm(1148.0).weatherScore(2)
                        .crewDutyHours(11.5)
                        .historicalDelayRate(0.41)
                        .gateTurnaroundMins(25)
                        .assignedGate("B3").assignedCrewId("CR-002")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // 6E-201: BOM→BLR midday — MEDIUM risk
                Flight.builder()
                        .flightId("6E-201")
                        .origin("BOM").destination("BLR")
                        .aircraftType("B737").aircraftAge(5)
                        .scheduledDeparture(base.plusHours(10))
                        .departureHour(10).dayOfWeek(dow).month(month)
                        .routeDistanceKm(845.0).weatherScore(1)
                        .crewDutyHours(4.0)
                        .historicalDelayRate(0.31)
                        .gateTurnaroundMins(40)
                        .assignedGate("A2").assignedCrewId("CR-003")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // AI-102: BOM→HYD evening — LOW risk
                Flight.builder()
                        .flightId("AI-102")
                        .origin("BOM").destination("HYD")
                        .aircraftType("A321").aircraftAge(6)
                        .scheduledDeparture(base.plusHours(16))
                        .departureHour(16).dayOfWeek(dow).month(month)
                        .routeDistanceKm(620.0).weatherScore(0)
                        .crewDutyHours(3.0)
                        .historicalDelayRate(0.27)
                        .gateTurnaroundMins(50)
                        .assignedGate("C1").assignedCrewId("CR-001")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // ── DEL (Delhi) — 3 departures ───────────────────────
                // AI-201: DEL→BLR morning — HIGH risk (same aircraft as AI-101)
                // CASCADE: if AI-101 delayed → AI-201 also delayed
                Flight.builder()
                        .flightId("AI-201")
                        .origin("DEL").destination("BLR")
                        .aircraftType("A320").aircraftAge(8)
                        .scheduledDeparture(base.plusHours(10))
                        .departureHour(10).dayOfWeek(dow).month(month)
                        .routeDistanceKm(1740.0).weatherScore(2)
                        .crewDutyHours(10.5)
                        .historicalDelayRate(0.44)
                        .gateTurnaroundMins(28)
                        .assignedGate("A1").assignedCrewId("CR-004")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // SG-301: DEL→BOM afternoon
                Flight.builder()
                        .flightId("SG-301")
                        .origin("DEL").destination("BOM")
                        .aircraftType("B737").aircraftAge(10)
                        .scheduledDeparture(base.plusHours(14))
                        .departureHour(14).dayOfWeek(dow).month(month)
                        .routeDistanceKm(1148.0).weatherScore(1)
                        .crewDutyHours(6.0)
                        .historicalDelayRate(0.39)
                        .gateTurnaroundMins(45)
                        .assignedGate("B2").assignedCrewId("CR-005")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // AI-203: DEL→CCU evening — HIGH risk (fog season)
                Flight.builder()
                        .flightId("AI-203")
                        .origin("DEL").destination("CCU")
                        .aircraftType("A321").aircraftAge(7)
                        .scheduledDeparture(base.plusHours(18))
                        .departureHour(18).dayOfWeek(dow).month(month)
                        .routeDistanceKm(1305.0).weatherScore(3)
                        .crewDutyHours(9.0)
                        .historicalDelayRate(0.48)
                        .gateTurnaroundMins(25)
                        .assignedGate("C2").assignedCrewId("CR-006")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // ── BLR (Bangalore) — 2 departures ───────────────────
                // 6E-401: BLR→BOM — CASCADE from 6E-201
                Flight.builder()
                        .flightId("6E-401")
                        .origin("BLR").destination("BOM")
                        .aircraftType("B737").aircraftAge(5)
                        .scheduledDeparture(base.plusHours(13))
                        .departureHour(13).dayOfWeek(dow).month(month)
                        .routeDistanceKm(845.0).weatherScore(0)
                        .crewDutyHours(2.0)
                        .historicalDelayRate(0.33)
                        .gateTurnaroundMins(55)
                        .assignedGate("A1").assignedCrewId("CR-001")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // AI-501: BLR→DEL afternoon
                Flight.builder()
                        .flightId("AI-501")
                        .origin("BLR").destination("DEL")
                        .aircraftType("A320").aircraftAge(9)
                        .scheduledDeparture(base.plusHours(15))
                        .departureHour(15).dayOfWeek(dow).month(month)
                        .routeDistanceKm(1740.0).weatherScore(1)
                        .crewDutyHours(7.0)
                        .historicalDelayRate(0.42)
                        .gateTurnaroundMins(35)
                        .assignedGate("B1").assignedCrewId("CR-003")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // ── HYD (Hyderabad) — 2 departures ───────────────────
                // SG-601: HYD→BOM — CASCADE from AI-102
                Flight.builder()
                        .flightId("SG-601")
                        .origin("HYD").destination("BOM")
                        .aircraftType("A321").aircraftAge(6)
                        .scheduledDeparture(base.plusHours(19))
                        .departureHour(19).dayOfWeek(dow).month(month)
                        .routeDistanceKm(620.0).weatherScore(1)
                        .crewDutyHours(8.0)
                        .historicalDelayRate(0.29)
                        .gateTurnaroundMins(40)
                        .assignedGate("A1").assignedCrewId("CR-002")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build(),

                // 6E-701: HYD→BLR evening — LOW risk
                Flight.builder()
                        .flightId("6E-701")
                        .origin("HYD").destination("BLR")
                        .aircraftType("ATR72").aircraftAge(3)
                        .scheduledDeparture(base.plusHours(17))
                        .departureHour(17).dayOfWeek(dow).month(month)
                        .routeDistanceKm(500.0).weatherScore(0)
                        .crewDutyHours(4.0)
                        .historicalDelayRate(0.18)
                        .gateTurnaroundMins(60)
                        .assignedGate("C1").assignedCrewId("CR-005")
                        .status(Flight.FlightStatus.SCHEDULED)
                        .createdAt(now).updatedAt(now).build()
        );

        flightRepository.saveAll(schedule);
        log.info("✅ Schedule generated — {} flights across "
                + "BOM, DEL, BLR, HYD", schedule.size());
    }
}