package com.aerosync.network;

import com.aerosync.datasource.DataSources;
import com.aerosync.model.AppUser;
import com.aerosync.model.Crew;
import com.aerosync.model.DependencyType;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.Gate;
import com.aerosync.model.UserRole;
import com.aerosync.model.WeatherReport;
import com.aerosync.monitoring.DisruptionMonitorService;
import com.aerosync.monitoring.MonitorCycleResult;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import com.aerosync.repository.WeatherReportRepository;
import com.aerosync.security.JwtFilter;
import com.aerosync.service.DisruptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4 over HTTP in the default (SYNTHETIC) data mode, through the real
 * filter, controllers, services, repositories and the disruption monitor
 * (H2 test database): pipeline status, airport-scoped live endpoints, and a
 * monitor-detected disruption going through the existing
 * propose → approve → execute workflow.
 */
class LiveDataAndMonitoringIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired WebApplicationContext context;
    @Autowired JwtFilter jwtFilter;
    @Autowired FlightRepository legacyFlights;
    @Autowired DisruptionRepository disruptions;
    @Autowired GateRepository gates;
    @Autowired CrewRepository crewMembers;
    @Autowired WeatherReportRepository weatherReports;
    @Autowired FlightInstanceRepository flightInstances;
    @Autowired DisruptionMonitorService monitor;

    /** No outbound LLM calls from the resolution flow. */
    @MockitoBean(name = "geminiWebClient")
    WebClient geminiWebClient;

    MockMvc mvc;
    AppUser bomOps;
    AppUser delOps;
    AppUser blrOps;
    AppUser bomViewer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(jwtFilter).build();
        insertReferenceAirports();
        bomOps = user("bom.ops", UserRole.OPERATIONS_CONTROLLER, true, "BOM");
        delOps = user("del.ops", UserRole.OPERATIONS_CONTROLLER, true, "DEL");
        blrOps = user("blr.ops", UserRole.OPERATIONS_CONTROLLER, true, "BLR");
        bomViewer = user("bom.viewer", UserRole.OPERATIONS_VIEWER, true, "BOM");
    }

    private ResultActions as(AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(user)));
    }

    // ── status and health ────────────────────────────────────────────────────

    @Test
    void pipelineStatusNeedsATokenAndReportsModeFeedsAndDependencies() throws Exception {
        mvc.perform(get("/api/system/status")).andExpect(status().isUnauthorized());

        String body = as(bomViewer, get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_mode").value("SYNTHETIC"))
                .andExpect(jsonPath("$.generated_at").value(notNullValue()))
                .andExpect(jsonPath("$.database.status").value("UP"))
                // The test profile points the ML service at a closed port.
                .andExpect(jsonPath("$.ml_service.status").value("DOWN"))
                .andExpect(jsonPath("$.feeds", hasSize(3)))
                .andExpect(jsonPath("$.feeds[0].feed").value("LIVE_STATES"))
                .andExpect(jsonPath("$.feeds[0].data_source").value("OPENSKY"))
                .andExpect(jsonPath("$.feeds[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.feeds[0].enabled").value(false))
                .andExpect(jsonPath("$.feeds[0].last_success_at").doesNotExist())
                .andExpect(jsonPath("$.feeds[1].feed").value("FLIGHT_LEGS"))
                .andExpect(jsonPath("$.feeds[2].feed").value("WEATHER"))
                .andExpect(jsonPath("$.feeds[2].status").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.monitoring.enabled").value(false))
                .andExpect(jsonPath("$.monitoring.status").value("DISABLED"))
                .andReturn().getResponse().getContentAsString();
        // Status describes the pipeline only: no credentials, no flight or airport data.
        assertTrue(!body.contains("secret") && !body.contains("password") && !body.contains("flight_number"),
                body);
    }

    @Test
    void readinessIsPublicAndSaysOnlyUpOrDown() throws Exception {
        mvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // ── airport-scoped live endpoints ────────────────────────────────────────

    @Test
    void liveAircraftAndWeatherAreLimitedToTheUsersAirports() throws Exception {
        as(bomOps, get("/api/airports/BOM/live-aircraft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airport").value("BOM"))
                // Nothing is fetched from OpenSky in SYNTHETIC mode, and nothing is made up instead.
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.aircraft", empty()));
        as(bomOps, get("/api/airports/BOM/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.source_configured").value(false))
                .andExpect(jsonPath("$.observation").doesNotExist());

        as(bomOps, get("/api/airports/DEL/live-aircraft")).andExpect(status().isForbidden());
        as(bomOps, get("/api/airports/DEL/weather")).andExpect(status().isForbidden());
        as(bomViewer, get("/api/airports/BLR/live-aircraft")).andExpect(status().isForbidden());
        as(bomOps, get("/api/airports/XYZ/live-aircraft")).andExpect(status().isNotFound());
        as(bomOps, get("/api/airports/b0m/weather")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/airports/BOM/live-aircraft")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/airports/BOM/weather")).andExpect(status().isUnauthorized());
    }

    @Test
    void aStoredObservationIsServedWithItsTimeAndAForecastIsKeptApart() throws Exception {
        Instant observedAt = Instant.now().minus(7, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        observation("BOM", 2, "moderate rain", observedAt);
        weatherReports.save(WeatherReport.builder()
                .airportId(airportRepository.findByIataCode("BOM").orElseThrow().getAirportId())
                .kind(WeatherReport.Kind.FORECAST).validAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .fetchedAt(Instant.now()).severityScore(3).description("heavy thunderstorm")
                .precipitationProbability(0.9).dataSource(DataSources.OPENWEATHERMAP).build());

        as(bomOps, get("/api/airports/BOM/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.observation.kind").value("OBSERVATION"))
                .andExpect(jsonPath("$.observation.severity_score").value(2))
                .andExpect(jsonPath("$.observation.description").value("moderate rain"))
                .andExpect(jsonPath("$.observation.data_source").value("OPENWEATHERMAP"))
                .andExpect(jsonPath("$.observation.valid_at").value(notNullValue()))
                .andExpect(jsonPath("$.observation_age_minutes").value(7))
                .andExpect(jsonPath("$.forecast", hasSize(1)))
                .andExpect(jsonPath("$.forecast[0].kind").value("FORECAST"))
                .andExpect(jsonPath("$.forecast[0].severity_score").value(3));
        // The existing score endpoint uses the observation (2), never the severe forecast (3).
        as(bomOps, get("/api/flights/weather/BOM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.weather_score").value(2));
    }

    // ── new disruption → recommendation → approval → execution ───────────────

    @Test
    void aDetectedDisruptionGoesThroughTheExistingApprovalWorkflow() throws Exception {
        gates.save(Gate.builder().gateNumber("A1").terminal("T1").compatibleTypes("A320")
                .available(false).currentFlightId("AI-900").build());
        crewMembers.save(Crew.builder().crewId("CR-901").name("Capt. BOM").role("PILOT").certifications("A320")
                .dutyHoursUsed(4.0).maxDutyHours(12.0).available(false).currentFlightId("AI-900").build());
        legacyFlights.save(Flight.builder().flightId("AI-900").origin("BOM").destination("DEL")
                .aircraftType("A320").status(Flight.FlightStatus.SCHEDULED).assignedGate("A1")
                .assignedCrewId("CR-901").crewDutyHours(5.0).gateTurnaroundMins(45).riskLevel("LOW")
                .delayProbability(0.2).scheduledDeparture(LocalDateTime.now().plusMinutes(75))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        observation("BOM", 3, "thunderstorm with heavy rain", Instant.now().minus(6, ChronoUnit.MINUTES));

        // 1. The monitor detects it. Nothing is proposed or executed.
        MonitorCycleResult cycle = monitor.runCycle();
        assertNull(cycle.error());
        assertEquals(1, cycle.opened());
        Disruption detected = disruptions.findByFlightId("AI-900").get(0);
        assertEquals(DisruptionStatus.DETECTED, detected.getStatus());
        assertEquals(DisruptionType.WEATHER_DELAY, detected.getType());
        assertEquals(DisruptionService.SOURCE_MONITOR, detected.getDetectionSource());
        assertNull(detected.getResolutionJson());
        assertEquals(Flight.FlightStatus.SCHEDULED, legacyFlights.findByFlightId("AI-900").orElseThrow().getStatus());
        assertEquals("A1", legacyFlights.findByFlightId("AI-900").orElseThrow().getAssignedGate());

        // A second cycle does not open it again.
        assertEquals(0, monitor.runCycle().opened());
        assertEquals(1, disruptions.findByFlightId("AI-900").size());

        // 2. It is listed with severity, confidence, source and detection time, for its airports only.
        as(bomViewer, get("/api/disruptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].flight_id").value("AI-900"))
                .andExpect(jsonPath("$[0].status").value("DETECTED"))
                .andExpect(jsonPath("$[0].severity").value("HIGH"))
                .andExpect(jsonPath("$[0].confidence").value(0.85))
                .andExpect(jsonPath("$[0].detection_source").value("MONITOR"))
                .andExpect(jsonPath("$[0].detection_rule").value("WEATHER_OBSERVED"))
                .andExpect(jsonPath("$[0].origin_airport").value("BOM"))
                .andExpect(jsonPath("$[0].data_origin").value("SYNTHETIC"))
                .andExpect(jsonPath("$[0].detected_at").value(notNullValue()))
                .andExpect(jsonPath("$[0].actionable").value(true));
        as(blrOps, get("/api/disruptions")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

        // 3. Only a controller of the departure airport may act.
        Long id = detected.getId();
        as(bomViewer, post("/api/agents/propose/{id}", id)).andExpect(status().isForbidden());
        as(delOps, post("/api/agents/propose/{id}", id)).andExpect(status().isForbidden());
        as(blrOps, post("/api/agents/approve/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertEquals(DisruptionStatus.DETECTED, disruptions.findById(id).orElseThrow().getStatus());

        // 4. Recommendation, then approval, then execution: the existing workflow.
        as(bomOps, post("/api/agents/propose/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_approval").value(true));
        Disruption proposed = disruptions.findById(id).orElseThrow();
        assertEquals(DisruptionStatus.PROPOSED, proposed.getStatus());
        assertNotNull(proposed.getProposedAt());

        as(bomOps, post("/api/agents/approve/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gate_resolution\":{\"action\":\"KEEP\"}}"))
                .andExpect(status().isOk());
        Disruption resolved = disruptions.findById(id).orElseThrow();
        assertEquals(DisruptionStatus.RESOLVED, resolved.getStatus());
        assertEquals("bom.ops", resolved.getApprovedBy());
        assertNotNull(resolved.getApprovedAt());
        assertNull(resolved.getResolutionNote(), "resolved by a person, not closed by the monitor");

        // 5. The monitor does not raise the handled event again.
        assertEquals(0, monitor.runCycle().opened());
        assertEquals(1, disruptions.findByFlightId("AI-900").size());
    }

    // ── advisory disruptions of network flights ──────────────────────────────

    @Test
    void networkDisruptionsAreAdvisoryScopedAndShownOnTheGraph() throws Exception {
        aircraft("VT-T09");
        ZonedDateTime firstDeparture = ZonedDateTime.now(IST).minusMinutes(30).truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime secondDeparture = firstDeparture.plusMinutes(180);
        FlightInstance inbound = flight("AI701", "HYD", "BLR", firstDeparture.toLocalDate(),
                firstDeparture.getHour(), firstDeparture.getMinute(), 120, "VT-T09");
        FlightInstance outbound = flight("AI702", "BLR", "HYD", secondDeparture.toLocalDate(),
                secondDeparture.getHour(), secondDeparture.getMinute(), 120, "VT-T09");
        dependency(inbound, outbound, DependencyType.AIRCRAFT_ROTATION, 0.9, 35, Map.of("station", "BLR"));
        inbound.setDelayMinutes(60);
        inbound.setStatus(FlightInstanceStatus.DEPARTED);
        flightInstances.saveAndFlush(inbound);

        MonitorCycleResult cycle = monitor.runCycle();

        assertNull(cycle.error());
        assertEquals(2, cycle.opened());
        // Inbound lands 60 min late and needs 35 min on the ground; the 60 min planned gap leaves 35 short.
        Disruption conflict = disruptions.findAll().stream()
                .filter(d -> d.getType() == DisruptionType.TURNAROUND_CONFLICT).findFirst().orElseThrow();
        assertEquals(outbound.getFlightId(), conflict.getFlightInstanceId());
        assertEquals(35, conflict.getDelayMinutes());
        assertEquals("BLR", conflict.getOriginAirport());
        assertEquals("HYD", conflict.getDestinationAirport());
        assertEquals(0, flightInstances.findById(outbound.getFlightId()).orElseThrow().getDelayMinutes(),
                "detection must not change the flight");

        // Listed for the airports of the flights only.
        as(blrOps, get("/api/disruptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].type", hasItem("TURNAROUND_CONFLICT")))
                .andExpect(jsonPath("$[*].type", hasItem("FLIGHT_DELAY")))
                .andExpect(jsonPath("$[*].actionable", not(hasItem(true))));
        as(bomOps, get("/api/disruptions")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
        as(delOps, get("/api/disruptions/active")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

        // Advisory: other airports get 403, the owning airport is told there is nothing to execute.
        Long id = conflict.getId();
        as(bomOps, post("/api/agents/propose/{id}", id)).andExpect(status().isForbidden());
        as(bomOps, post("/api/agents/resolve/{id}", id)).andExpect(status().isForbidden());
        as(blrOps, post("/api/agents/propose/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_available"))
                .andExpect(jsonPath("$.error").value(containsString("advisory")));
        assertEquals(DisruptionStatus.DETECTED, disruptions.findById(id).orElseThrow().getStatus());

        // The graph highlights the flight for those who may see it, and only for them.
        as(blrOps, get("/api/graph/network").param("date", secondDeparture.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_mode").value("SYNTHETIC"))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + node(outbound) + "')].disruption.disruption_type")
                        .value(hasItem("TURNAROUND_CONFLICT")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + node(outbound) + "')].disruption.severity")
                        .value(hasItem("MEDIUM")))
                .andExpect(jsonPath("$.nodes[?(@.id=='" + node(outbound) + "')].provenance.data_source")
                        .value(hasItem("MANUAL_ENTRY")));
        as(bomOps, get("/api/graph/network").param("date", secondDeparture.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.type=='FLIGHT')]", empty()));
        as(bomOps, get("/api/graph/flights/{flightId}", outbound.getFlightId()))
                .andExpect(status().isForbidden());
    }

    private void observation(String iata, int score, String description, Instant observedAt) {
        weatherReports.saveAndFlush(WeatherReport.builder()
                .airportId(airportRepository.findByIataCode(iata).orElseThrow().getAirportId())
                .kind(WeatherReport.Kind.OBSERVATION).validAt(observedAt).fetchedAt(observedAt)
                .severityScore(score).description(description).dataSource(DataSources.OPENWEATHERMAP).build());
    }
}
