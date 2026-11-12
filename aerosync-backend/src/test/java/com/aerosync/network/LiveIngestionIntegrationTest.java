package com.aerosync.network;

import com.aerosync.datasource.DataSourceMode;
import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.datasource.DataSources;
import com.aerosync.ingestion.AirportDirectoryProvider;
import com.aerosync.ingestion.CallsignMapper;
import com.aerosync.ingestion.FlightLegStore;
import com.aerosync.ingestion.IngestionRunRecorder;
import com.aerosync.ingestion.IngestionSettings;
import com.aerosync.ingestion.LiveStateStore;
import com.aerosync.ingestion.OpenSkyIngestionJob;
import com.aerosync.ingestion.StubHttpServer;
import com.aerosync.ingestion.StubHttpServer.Reply;
import com.aerosync.ingestion.opensky.OpenSkyClient;
import com.aerosync.ingestion.opensky.OpenSkySettings;
import com.aerosync.model.AppUser;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.IngestionRun;
import com.aerosync.model.ScheduleBasis;
import com.aerosync.model.UserRole;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import com.aerosync.repository.IngestionCheckpointRepository;
import com.aerosync.repository.IngestionRunRepository;
import com.aerosync.repository.LiveAircraftStateRepository;
import com.aerosync.security.JwtFilter;
import com.aerosync.service.FlightNetworkService.NewFlight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * External data → database → backend API, in HYBRID mode: the real ingestion
 * job, stores, JPA mappings and repositories (H2) are fed from a local
 * stand-in for the OpenSky API, and the result is read back through the
 * secured HTTP endpoints.
 *
 * <p>The context never contacts OpenSky: its own client points at a closed
 * local port and its scheduled ticks are pushed an hour out; the test drives
 * a job wired to the stand-in instead.</p>
 */
@TestPropertySource(properties = {
        "aerosync.data.mode=HYBRID",
        "aerosync.opensky.base-url=http://127.0.0.1:9/api",
        "aerosync.opensky.token-url=http://127.0.0.1:9/token",
        "aerosync.ingestion.initial-delay-ms=3600000",
        "aerosync.ingestion.flight-legs-initial-delay-ms=3600000"
})
class LiveIngestionIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired WebApplicationContext context;
    @Autowired JwtFilter jwtFilter;
    @Autowired AirportDirectoryProvider airportDirectoryProvider;
    @Autowired LiveStateStore liveStateStore;
    @Autowired FlightLegStore flightLegStore;
    @Autowired IngestionCheckpointRepository checkpoints;
    @Autowired IngestionRunRecorder runRecorder;
    @Autowired IngestionRunRepository runs;
    @Autowired LiveAircraftStateRepository liveStates;
    @Autowired FlightScheduleRepository schedules;
    @Autowired FlightInstanceRepository flightInstances;

    StubHttpServer openSky;
    MockMvc mvc;
    OpenSkyIngestionJob job;
    AppUser bomOps;
    AppUser delOps;
    AppUser pnqViewer;
    LocalDate yesterday;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(jwtFilter).build();
        insertReferenceAirports();
        airportDirectoryProvider.refresh();
        bomOps = user("bom.ops", UserRole.OPERATIONS_CONTROLLER, true, "BOM");
        delOps = user("del.ops", UserRole.OPERATIONS_CONTROLLER, true, "DEL");
        pnqViewer = user("pnq.viewer", UserRole.OPERATIONS_VIEWER, true, "PNQ");
        yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);

        openSky = new StubHttpServer();
        OpenSkySettings settings = new OpenSkySettings(openSky.url() + "/api", openSky.url() + "/token", "", "",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1, Duration.ofMillis(10), Duration.ofMillis(10));
        IngestionSettings ingestion = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 1, 40,
                IngestionSettings.STANDARD_DAILY_CREDITS, Duration.ofSeconds(60), Duration.ofMinutes(30));
        job = new OpenSkyIngestionJob(new DataSourcePolicy(DataSourceMode.HYBRID), ingestion,
                new OpenSkyClient(settings), airportDirectoryProvider, new CallsignMapper(), liveStateStore,
                flightLegStore, checkpoints, runRecorder, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        openSky.close();
        airportDirectoryProvider.refresh();
    }

    private ResultActions as(AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(user)));
    }

    private static String state(String icao24, String callsign, double latitude, double longitude,
                                long lastContact, boolean onGround) {
        return "[\"" + icao24 + "\",\"" + callsign + "\",\"India\"," + lastContact + "," + lastContact + ","
                + longitude + "," + latitude + ",900.0," + onGround + ",75.0,270.0,-3.0,null,950.0,null,false,0]";
    }

    private String flight(String icao24, String callsign, String from, String to, int departureHourUtc,
                          int blockMinutes) {
        long departure = yesterday.atStartOfDay(ZoneOffset.UTC).plusHours(departureHourUtc).toEpochSecond();
        return "{\"icao24\":\"" + icao24 + "\",\"firstSeen\":" + departure + ",\"estDepartureAirport\":\"" + from
                + "\",\"lastSeen\":" + (departure + blockMinutes * 60L) + ",\"estArrivalAirport\":\"" + to
                + "\",\"callsign\":\"" + callsign + "\"}";
    }

    @Test
    void liveAircraftAreStoredOncePerAircraftAndServedToTheirAirportOnly() throws Exception {
        long now = Instant.now().getEpochSecond();
        openSky.on("/api/states/all", Reply.of(200, "{\"time\":" + now + ",\"states\":["
                + state("800c6d", "IGO1454 ", 19.20, 72.95, now - 3, false) + ","
                + state("800c6e", "AIC5BH  ", 19.09, 72.87, now - 5, true) + ","
                + state("800c70", "SEJ8001 ", 28.60, 77.20, now - 4, false) + "]}"));

        IngestionRun first = job.pollStates();
        IngestionRun second = job.pollStates();     // the same snapshot again

        assertEquals(IngestionRun.Status.SUCCESS, first.getStatus());
        assertEquals(3, first.getRecordsInserted());
        assertEquals(0, second.getRecordsInserted());
        assertEquals(3, second.getRecordsUnchanged());
        assertEquals(3, liveStates.count());
        assertEquals(DataSources.OPENSKY, liveStates.findById("800c6d").orElseThrow().getDataSource());
        assertEquals(2, runs.count());

        as(bomOps, get("/api/airports/BOM/live-aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_source").value("OPENSKY"))
                .andExpect(jsonPath("$.aircraft", hasSize(2)))
                .andExpect(jsonPath("$.on_ground").value(1))
                .andExpect(jsonPath("$.airborne").value(1))
                .andExpect(jsonPath("$.aircraft[*].icao24", hasItem("800c6d")))
                .andExpect(jsonPath("$.aircraft[?(@.icao24=='800c6d')].flight_number").value(hasItem("6E1454")))
                .andExpect(jsonPath("$.aircraft[?(@.icao24=='800c6d')].phase").value(hasItem("DESCENDING")))
                // An ATC callsign is shown as it is; no flight number is derived from it.
                .andExpect(jsonPath("$.aircraft[?(@.icao24=='800c6e')].callsign").value(hasItem("AIC5BH")))
                .andExpect(jsonPath("$.aircraft[?(@.icao24=='800c6e')].flight_number").value(empty()))
                // The aircraft near Delhi is not part of Mumbai's answer.
                .andExpect(jsonPath("$.aircraft[*].icao24", not(hasItem("800c70"))));
        as(delOps, get("/api/airports/DEL/live-aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aircraft[*].icao24", contains("800c70")));
        as(delOps, get("/api/airports/BOM/live-aircraft")).andExpect(status().isForbidden());
        as(pnqViewer, get("/api/airports/PNQ/live-aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aircraft", empty()));
    }

    @Test
    void importedLegsAppearInTheGraphLabelledAndScopedAndAreNeverDuplicated() throws Exception {
        openSky.on("/api/flights/departure", Reply.of(200, "["
                + flight("800c6d", "IGO0679 ", "VABB", "VIDP", 4, 125) + ","
                + flight("800c6e", "AIC0101 ", "VABB", "VAPO", 9, 35) + ","
                + flight("800c6f", "AIC5BH  ", "VABB", "VIDP", 6, 120) + "]"));

        IngestionRun first = job.importFlightLegs().orElseThrow();
        assertEquals(IngestionRun.Status.SUCCESS, first.getStatus());
        assertEquals(2, first.getRecordsInserted());
        assertTrue(first.getDetails().contains("UNMAPPED_CALLSIGN"), first.getDetails());
        assertTrue(job.importFlightLegs().isEmpty(), "every airport and day is checkpointed");

        // Even when everything is offered again, nothing is duplicated.
        checkpoints.deleteAll();
        IngestionRun again = job.importFlightLegs().orElseThrow();
        assertEquals(0, again.getRecordsInserted());
        assertEquals(2, schedules.countByDataSource(DataSources.OPENSKY));

        FlightSchedule leg = schedules.findAll().stream()
                .filter(s -> s.getFlightNumber().equals("6E679")).findFirst().orElseThrow();
        assertEquals(DataOrigin.IMPORTED, leg.getDataOrigin());
        assertEquals(ScheduleBasis.OBSERVED, leg.getScheduleBasis());
        assertNull(leg.getAircraftType());
        FlightInstance instance = flightInstances.findBySchedule_ScheduleId(leg.getScheduleId()).orElseThrow();
        assertEquals(FlightInstanceStatus.ARRIVED, instance.getStatus());

        String date = leg.getServiceDate().toString();
        String legNode = "flight:" + instance.getFlightId();
        as(bomOps, get("/api/graph/network").param("date", date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_mode").value("HYBRID"))
                .andExpect(jsonPath("$.data_sources", hasItem("OPENSKY")))
                .andExpect(jsonPath("$.synthetic_data").value(false))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + legNode + "')].data_origin").value(hasItem("IMPORTED")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + legNode + "')].provenance.data_source")
                        .value(hasItem("OPENSKY")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + legNode + "')].provenance.schedule_basis")
                        .value(hasItem("OBSERVED")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + legNode + "')].provenance.callsign")
                        .value(hasItem("IGO0679")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + legNode + "')].status").value(hasItem("ARRIVED")))
                .andExpect(jsonPath("$.nodes[?(@.type=='FLIGHT')]", hasSize(2)));
        // Delhi sees the Mumbai-Delhi leg but not Mumbai-Pune; Pune the other way round.
        as(delOps, get("/api/graph/network").param("date", date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.type=='FLIGHT')].flight_number", contains("6E679")));
        as(pnqViewer, get("/api/graph/network").param("date", date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.type=='FLIGHT')].flight_number", contains("AI101")));
        as(pnqViewer, get("/api/graph/flights/{flightId}", instance.getFlightId()))
                .andExpect(status().isForbidden());
        as(bomOps, get("/api/airports/BOM/operations").param("date", date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_mode").value("HYBRID"))
                .andExpect(jsonPath("$.departures[*].provenance.data_source", hasItem("OPENSKY")));
    }

    @Test
    void aSyntheticLegIsNotReplacedByAnExternalRecordWithTheSameKey() throws Exception {
        Instant departure = yesterday.atStartOfDay(ZoneOffset.UTC).plusHours(3).toInstant();
        LocalDate serviceDate = departure.atZone(IST).toLocalDate();
        FlightInstance synthetic = flightNetworkService.createFlight(new NewFlight("6E679", null, "BOM", "DEL",
                serviceDate, departure, departure.plus(130, ChronoUnit.MINUTES), "A320", null,
                DataOrigin.SYNTHETIC));
        openSky.on("/api/flights/departure", Reply.of(200, "["
                + flight("800c6d", "IGO0679 ", "VABB", "VIDP", 4, 125) + "]"));

        IngestionRun run = job.importFlightLegs().orElseThrow();

        assertTrue(run.getDetails().contains("LEG_EXISTS_FROM_OTHER_SOURCE"), run.getDetails());
        assertEquals(0, schedules.countByDataSource(DataSources.OPENSKY));
        FlightSchedule unchanged = schedules.findById(synthetic.getSchedule().getScheduleId()).orElseThrow();
        assertEquals(DataOrigin.SYNTHETIC, unchanged.getDataOrigin());
        assertEquals(DataSources.SYNTHETIC_GENERATOR, unchanged.getDataSource());
        assertEquals(departure, unchanged.getScheduledDeparture());
        as(bomOps, get("/api/graph/network").param("date", serviceDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synthetic_data").value(true))
                .andExpect(jsonPath("$.nodes[?(@.type=='FLIGHT')].provenance.data_source",
                        contains("SYNTHETIC_GENERATOR")));
    }
}
