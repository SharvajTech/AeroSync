package com.aerosync.network;

import com.aerosync.model.Aircraft;
import com.aerosync.model.AircraftStatus;
import com.aerosync.model.Airport;
import com.aerosync.model.AirportStatus;
import com.aerosync.model.AppUser;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.UserRole;
import com.aerosync.repository.AircraftRepository;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.AppUserRepository;
import com.aerosync.security.AirportScope;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.security.JwtUtil;
import com.aerosync.service.DataSeederService;
import com.aerosync.service.FlightNetworkService;
import com.aerosync.service.FlightNetworkService.NewDependency;
import com.aerosync.service.FlightNetworkService.NewFlight;
import com.aerosync.synthetic.ReferenceAirports;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Spring context with the in-memory H2 test database. Every test runs in a
 * transaction that is rolled back, so tests never see each other's data.
 * The legacy demo seeder is replaced by a mock so it does not populate the
 * legacy tables.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
abstract class OperationalNetworkTestSupport {

    static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @MockitoBean
    DataSeederService dataSeederService;

    @Autowired
    AirportRepository airportRepository;

    @Autowired
    AircraftRepository aircraftRepository;

    @Autowired
    FlightNetworkService flightNetworkService;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    /** Password of every user created by {@link #user}. Test-only value. */
    static final String TEST_PASSWORD = "test-only-Passw0rd!";

    /** All reference airport codes (BOM, DEL, BLR, ... BBI). */
    static String[] allAirportCodes() {
        return ReferenceAirports.ALL.stream().map(ReferenceAirports.ReferenceAirport::iataCode).toArray(String[]::new);
    }

    /** A user assigned to the given (existing) airports, password {@link #TEST_PASSWORD}. */
    AppUser user(String username, UserRole role, boolean enabled, String... airportCodes) {
        Set<Airport> airports = new LinkedHashSet<>();
        for (String code : airportCodes) {
            airports.add(airportRepository.findByIataCode(code)
                    .orElseThrow(() -> new IllegalStateException("Test airport " + code + " missing")));
        }
        return appUserRepository.saveAndFlush(AppUser.builder()
                .username(username)
                .displayName(username + " (test)")
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .role(role)
                .enabled(enabled)
                .airports(airports)
                .build());
    }

    /** {@code Authorization} header value with a valid token for the user. */
    String bearer(AppUser user) {
        AirportScope scope = AirportScope.of(user.getAirports().stream().map(Airport::getIataCode).toList());
        return "Bearer " + jwtUtil.issue(new AuthenticatedUser(user.getUserId(), user.getUsername(),
                user.getDisplayName(), user.getRole(), scope)).token();
    }

    /**
     * BOM -AI101-> BLR -AI202-> DEL with a passenger connection AI101 -> AI202,
     * AI101's aircraft (VT-T01) continuing on AI505 BLR-HYD and AI506 HYD-BLR,
     * and AI202 flown by VT-T02 after AI201 DEL-BLR. Plus an unrelated
     * DEL-BOM flight and AI101 again on the next day.
     */
    record Sample(FlightInstance ai101, FlightInstance ai505, FlightInstance ai506, FlightInstance ai201,
                  FlightInstance ai202, FlightInstance sg8001, FlightInstance ai101NextDay) {
    }

    void insertReferenceAirports() {
        for (ReferenceAirports.ReferenceAirport reference : ReferenceAirports.ALL) {
            if (airportRepository.findByIataCode(reference.iataCode()).isEmpty()) {
                airportRepository.save(Airport.builder()
                        .airportId(reference.airportId())
                        .iataCode(reference.iataCode())
                        .airportName(reference.airportName())
                        .city(reference.city())
                        .timezone(reference.timezone())
                        .latitude(reference.latitude())
                        .longitude(reference.longitude())
                        .status(AirportStatus.OPERATIONAL)
                        .build());
            }
        }
        airportRepository.flush();
    }

    Sample createSampleNetwork() {
        insertReferenceAirports();
        aircraft("VT-T01");
        aircraft("VT-T02");
        aircraft("VT-T03");

        FlightInstance ai101 = flight("AI101", "BOM", "BLR", DAY, 7, 0, 100, "VT-T01");
        FlightInstance ai505 = flight("AI505", "BLR", "HYD", DAY, 9, 30, 70, "VT-T01");
        FlightInstance ai506 = flight("AI506", "HYD", "BLR", DAY, 11, 40, 70, "VT-T01");
        FlightInstance ai201 = flight("AI201", "DEL", "BLR", DAY, 6, 15, 170, "VT-T02");
        FlightInstance ai202 = flight("AI202", "BLR", "DEL", DAY, 10, 30, 170, "VT-T02");
        FlightInstance sg8001 = flight("SG8001", "DEL", "BOM", DAY, 8, 0, 125, "VT-T03");
        FlightInstance ai101NextDay = flight("AI101", "BOM", "BLR", DAY.plusDays(1), 7, 0, 100, "VT-T01");

        dependency(ai101, ai202, DependencyType.PASSENGER_CONNECTION, 0.45, 45,
                Map.of("hub", "BLR", "connecting_passengers", 26));
        dependency(ai101, ai505, DependencyType.AIRCRAFT_ROTATION, 0.93, 30, Map.of("station", "BLR"));
        dependency(ai505, ai506, DependencyType.AIRCRAFT_ROTATION, 0.9, 30, Map.of("station", "HYD"));
        dependency(ai201, ai202, DependencyType.AIRCRAFT_ROTATION, 0.8, 30, Map.of("station", "BLR"));
        return new Sample(ai101, ai505, ai506, ai201, ai202, sg8001, ai101NextDay);
    }

    Aircraft aircraft(String registration) {
        return aircraftRepository.save(Aircraft.builder()
                .registration(registration)
                .aircraftType("A320")
                .operatingAirline(registration.equals("VT-T03") ? "SG" : "AI")
                .status(AircraftStatus.ACTIVE)
                .dataOrigin(DataOrigin.MANUAL)
                .build());
    }

    FlightInstance flight(String number, String origin, String destination, LocalDate date,
                          int hour, int minute, int blockMinutes, String registration) {
        Instant departure = ist(date, hour, minute);
        return flightNetworkService.createFlight(new NewFlight(number, null, origin, destination, date,
                departure, departure.plusSeconds(blockMinutes * 60L), "A320", registration, DataOrigin.MANUAL));
    }

    void dependency(FlightInstance source, FlightInstance target, DependencyType type, double weight,
                    Integer minimumConnectionMinutes, Map<String, Object> metadata) {
        flightNetworkService.createDependency(new NewDependency(source.getFlightId(), target.getFlightId(), type,
                weight, minimumConnectionMinutes, metadata, DataOrigin.MANUAL));
    }

    static Instant ist(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), IST).toInstant();
    }

    static String node(FlightInstance flight) {
        return "flight:" + flight.getFlightId();
    }
}
