package com.aerosync.service;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.exception.OperationNotAvailableException;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import com.aerosync.security.AirportScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleGeneratorService {

    /** Departure airports of the generated schedule. */
    public static final Set<String> ORIGIN_AIRPORTS = Set.of("BOM", "DEL", "BLR", "HYD");

    private final FlightRepository flightRepository;
    private final WeatherService   weatherService;
    private final DataSourcePolicy dataSourcePolicy;

    /**
     * Regenerates the schedule for a user. The generator deletes every legacy
     * flight and creates new ones departing from {@link #ORIGIN_AIRPORTS}, so
     * the user must be authorized for all of those and for every departure
     * airport that currently has flights (Stage 3).
     */
    public void generateDailySchedule(AirportScope scope) {
        requireSyntheticData();
        if (!scope.isUnrestricted()) {
            Set<String> affected = new TreeSet<>(ORIGIN_AIRPORTS);
            for (String origin : flightRepository.findDistinctOrigins()) {
                affected.add(origin.trim().toUpperCase(Locale.ROOT));
            }
            List<String> missing = affected.stream().filter(code -> !scope.includes(code)).toList();
            if (!missing.isEmpty()) {
                throw new AccessForbiddenException("Regenerating the schedule replaces the flights of "
                        + missing.size() + " airport(s) you are not authorized for; it needs access to every "
                        + "departure airport (" + String.join(", ", ORIGIN_AIRPORTS.stream().sorted().toList())
                        + " and any other airport with flights)");
            }
        }
        generateDailySchedule();
    }

    /** Stage 4: the generated schedule is synthetic, so it does not exist in LIVE mode. */
    private void requireSyntheticData() {
        if (!dataSourcePolicy.mode().generatesSyntheticData()) {
            throw new OperationNotAvailableException("The demonstration schedule is synthetic and is not "
                    + "generated in data mode " + dataSourcePolicy.mode());
        }
    }

    public void generateDailySchedule() {
        requireSyntheticData();
        flightRepository.deleteAll();
        log.info("Generating fresh daily schedule...");

        // Fetch live weather for all airports
        Map<String, Integer> weather =
                weatherService.getWeatherForAllAirports();
        log.info("Live weather for schedule: {}", weather);

        LocalDateTime base  = LocalDate.now().atStartOfDay();
        int dow   = base.getDayOfWeek().getValue() % 7;
        int month = base.getMonthValue();
        LocalDateTime now   = LocalDateTime.now();

        // Helper to get weather score safely
        int bomW = weather.getOrDefault("BOM", 1);
        int delW = weather.getOrDefault("DEL", 1);
        int blrW = weather.getOrDefault("BLR", 0);
        int hydW = weather.getOrDefault("HYD", 0);

        List<Flight> schedule = List.of(

            // ── BOM departures ────────────────────────────────────
            Flight.builder()
                .flightId("AI-101")
                .origin("BOM").destination("DEL")
                .aircraftType("A320").aircraftAge(8)
                .scheduledDeparture(base.plusHours(7))
                .departureHour(7).dayOfWeek(dow).month(month)
                .routeDistanceKm(1148.0)
                .weatherScore(bomW)          // ← live weather
                .crewDutyHours(11.5)
                .historicalDelayRate(0.41)
                .gateTurnaroundMins(25)
                .assignedGate("B3").assignedCrewId("CR-002")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("6E-201")
                .origin("BOM").destination("BLR")
                .aircraftType("B737").aircraftAge(5)
                .scheduledDeparture(base.plusHours(10))
                .departureHour(10).dayOfWeek(dow).month(month)
                .routeDistanceKm(845.0)
                .weatherScore(bomW)
                .crewDutyHours(4.0)
                .historicalDelayRate(0.31)
                .gateTurnaroundMins(40)
                .assignedGate("A2").assignedCrewId("CR-003")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("AI-102")
                .origin("BOM").destination("HYD")
                .aircraftType("A321").aircraftAge(6)
                .scheduledDeparture(base.plusHours(16))
                .departureHour(16).dayOfWeek(dow).month(month)
                .routeDistanceKm(620.0)
                .weatherScore(bomW)
                .crewDutyHours(3.0)
                .historicalDelayRate(0.27)
                .gateTurnaroundMins(50)
                .assignedGate("C1").assignedCrewId("CR-001")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            // ── DEL departures ────────────────────────────────────
            // AI-201 departs DEL 3h after AI-101 arrives
            // Natural cascade chain: AI-101 delayed → AI-201 delayed
            Flight.builder()
                .flightId("AI-201")
                .origin("DEL").destination("BLR")
                .aircraftType("A320").aircraftAge(8)
                .scheduledDeparture(base.plusHours(10))
                .departureHour(10).dayOfWeek(dow).month(month)
                .routeDistanceKm(1740.0)
                .weatherScore(delW)
                .crewDutyHours(10.5)
                .historicalDelayRate(0.44)
                .gateTurnaroundMins(28)
                .assignedGate("A1").assignedCrewId("CR-004")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("SG-301")
                .origin("DEL").destination("BOM")
                .aircraftType("B737").aircraftAge(10)
                .scheduledDeparture(base.plusHours(14))
                .departureHour(14).dayOfWeek(dow).month(month)
                .routeDistanceKm(1148.0)
                .weatherScore(delW)
                .crewDutyHours(6.0)
                .historicalDelayRate(0.39)
                .gateTurnaroundMins(45)
                .assignedGate("B2").assignedCrewId("CR-005")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("AI-203")
                .origin("DEL").destination("CCU")
                .aircraftType("A321").aircraftAge(7)
                .scheduledDeparture(base.plusHours(18))
                .departureHour(18).dayOfWeek(dow).month(month)
                .routeDistanceKm(1305.0)
                .weatherScore(delW)
                .crewDutyHours(9.0)
                .historicalDelayRate(0.48)
                .gateTurnaroundMins(25)
                .assignedGate("C2").assignedCrewId("CR-006")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            // ── BLR departures ────────────────────────────────────
            Flight.builder()
                .flightId("6E-401")
                .origin("BLR").destination("BOM")
                .aircraftType("B737").aircraftAge(5)
                .scheduledDeparture(base.plusHours(13))
                .departureHour(13).dayOfWeek(dow).month(month)
                .routeDistanceKm(845.0)
                .weatherScore(blrW)
                .crewDutyHours(2.0)
                .historicalDelayRate(0.33)
                .gateTurnaroundMins(55)
                .assignedGate("A1").assignedCrewId("CR-001")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("AI-501")
                .origin("BLR").destination("DEL")
                .aircraftType("A320").aircraftAge(9)
                .scheduledDeparture(base.plusHours(15))
                .departureHour(15).dayOfWeek(dow).month(month)
                .routeDistanceKm(1740.0)
                .weatherScore(blrW)
                .crewDutyHours(7.0)
                .historicalDelayRate(0.42)
                .gateTurnaroundMins(35)
                .assignedGate("B1").assignedCrewId("CR-003")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            // ── HYD departures ────────────────────────────────────
            Flight.builder()
                .flightId("SG-601")
                .origin("HYD").destination("BOM")
                .aircraftType("A321").aircraftAge(6)
                .scheduledDeparture(base.plusHours(19))
                .departureHour(19).dayOfWeek(dow).month(month)
                .routeDistanceKm(620.0)
                .weatherScore(hydW)
                .crewDutyHours(8.0)
                .historicalDelayRate(0.29)
                .gateTurnaroundMins(40)
                .assignedGate("A1").assignedCrewId("CR-002")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build(),

            Flight.builder()
                .flightId("6E-701")
                .origin("HYD").destination("BLR")
                .aircraftType("ATR72").aircraftAge(3)
                .scheduledDeparture(base.plusHours(17))
                .departureHour(17).dayOfWeek(dow).month(month)
                .routeDistanceKm(500.0)
                .weatherScore(hydW)
                .crewDutyHours(4.0)
                .historicalDelayRate(0.18)
                .gateTurnaroundMins(60)
                .assignedGate("C1").assignedCrewId("CR-005")
                .status(Flight.FlightStatus.SCHEDULED)
                .createdAt(now).updatedAt(now).build()
        );

        // Everything generated here is demonstration data and is labelled as such.
        schedule.forEach(flight -> flight.setDataOrigin(DataOrigin.SYNTHETIC));
        flightRepository.saveAll(schedule);
        log.info("✅ Schedule generated — {} flights with "
               + "live weather across BOM, DEL, BLR, HYD",
                schedule.size());
    }
}