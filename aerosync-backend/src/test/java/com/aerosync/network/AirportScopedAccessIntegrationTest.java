package com.aerosync.network;

import com.aerosync.model.AppUser;
import com.aerosync.model.Crew;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.Gate;
import com.aerosync.model.UserRole;
import com.aerosync.repository.CrewRepository;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.repository.GateRepository;
import com.aerosync.security.AirportScope;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.security.JwtFilter;
import com.aerosync.security.JwtUtil;
import com.aerosync.synthetic.ReferenceAirports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3 security over HTTP, through the real {@link JwtFilter}, controllers,
 * services and repositories (H2 test database).
 *
 * <p>Two airports, BOM and DEL, with users bom.ops (BOM), del.ops (DEL),
 * multi.ops (BOM + DEL) and bom.viewer (BOM, read-only). Every cross-airport
 * attempt must be rejected even when ids and parameters are edited by hand,
 * and must leave the database unchanged.</p>
 *
 * <p>Sample network (see {@link OperationalNetworkTestSupport}): AI101 BOM-BLR,
 * SG8001 DEL-BOM, AI201 DEL-BLR, AI202 BLR-DEL, AI505 BLR-HYD, AI506 HYD-BLR.
 * Legacy operations data: AI-900 BOM-DEL and AI-901 DEL-BLR, each with a
 * disruption.</p>
 */
class AirportScopedAccessIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired WebApplicationContext context;
    @Autowired JwtFilter jwtFilter;
    @Autowired FlightRepository legacyFlights;
    @Autowired DisruptionRepository disruptions;
    @Autowired GateRepository gates;
    @Autowired CrewRepository crewMembers;

    /** No outbound LLM calls from the resolution flow. */
    @MockitoBean(name = "geminiWebClient")
    WebClient geminiWebClient;

    @Value("${jwt.secret}")
    String jwtSecret;

    MockMvc mvc;
    Sample sample;
    AppUser bomOps;
    AppUser delOps;
    AppUser multiOps;
    AppUser bomViewer;
    Disruption bomDisruption;
    Disruption delDisruption;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(jwtFilter).build();
        sample = createSampleNetwork();
        bomOps = user("bom.ops", UserRole.OPERATIONS_CONTROLLER, true, "BOM");
        delOps = user("del.ops", UserRole.OPERATIONS_CONTROLLER, true, "DEL");
        multiOps = user("multi.ops", UserRole.OPERATIONS_CONTROLLER, true, "BOM", "DEL");
        bomViewer = user("bom.viewer", UserRole.OPERATIONS_VIEWER, true, "BOM");

        gates.save(Gate.builder().gateNumber("A1").terminal("T1").compatibleTypes("A320")
                .available(false).currentFlightId("AI-900").build());
        gates.save(Gate.builder().gateNumber("C1").terminal("T3").compatibleTypes("A320")
                .available(false).currentFlightId("AI-901").build());
        crewMembers.save(Crew.builder().crewId("CR-901").name("Capt. BOM").role("PILOT").certifications("A320")
                .dutyHoursUsed(4.0).maxDutyHours(12.0).available(false).currentFlightId("AI-900").build());
        crewMembers.save(Crew.builder().crewId("CR-902").name("Capt. DEL").role("PILOT").certifications("A320")
                .dutyHoursUsed(4.0).maxDutyHours(12.0).available(false).currentFlightId("AI-901").build());
        legacyFlights.save(legacyFlight("AI-900", "BOM", "DEL", "A1", "CR-901"));
        legacyFlights.save(legacyFlight("AI-901", "DEL", "BLR", "C1", "CR-902"));
        bomDisruption = disruptions.save(disruption("AI-900"));
        delDisruption = disruptions.save(disruption("AI-901"));
    }

    private static Flight legacyFlight(String id, String origin, String destination, String gate, String crew) {
        return Flight.builder().flightId(id).origin(origin).destination(destination).aircraftType("A320")
                .status(Flight.FlightStatus.SCHEDULED).assignedGate(gate).assignedCrewId(crew)
                .crewDutyHours(5.0).gateTurnaroundMins(45).riskLevel("HIGH")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private static Disruption disruption(String flightId) {
        return Disruption.builder().flightId(flightId).type(DisruptionType.WEATHER_DELAY)
                .status(DisruptionStatus.DETECTED).delayMinutes(60).passengersAffected(100)
                .description("test").detectedAt(LocalDateTime.now()).build();
    }

    private ResultActions as(AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(user)));
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    void loginIssuesATokenThatAuthenticatesLaterRequests() throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(" BOM.Ops ", TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.token").value(notNullValue()))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_at").value(notNullValue()))
                .andExpect(jsonPath("$.user.username").value("bom.ops"))
                .andExpect(jsonPath("$.user.role").value("OPERATIONS_CONTROLLER"))
                .andExpect(jsonPath("$.user.can_operate").value(true))
                .andExpect(jsonPath("$.user.airports[*].iata_code").value(containsInAnyOrder("BOM")))
                .andExpect(jsonPath("$.user.password_hash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bom.ops"))
                .andExpect(jsonPath("$.airports[*].iata_code").value(containsInAnyOrder("BOM")));
        assertTrue(appUserRepository.findWithAirportsByUsername("bom.ops").orElseThrow().getLastLoginAt() != null);
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledAccountGetTheSameAnswer() throws Exception {
        user("disabled.ops", UserRole.OPERATIONS_CONTROLLER, false, "BOM");
        for (String[] credentials : new String[][]{{"del.ops", "wrong-password"}, {"nobody", TEST_PASSWORD},
                {"disabled.ops", TEST_PASSWORD}}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(credentials[0], credentials[1])))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Invalid username or password"))
                    .andExpect(jsonPath("$.code").value("invalid_credentials"))
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
    }

    @Test
    void loginRequiresUsernameAndPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody("bom.ops", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarily() throws Exception {
        user("lockout.ops", UserRole.OPERATIONS_CONTROLLER, true, "BOM");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("lockout.ops", "guess-" + i)))
                    .andExpect(status().isUnauthorized());
        }
        // Even the right password is refused while locked.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("lockout.ops", TEST_PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("too_many_attempts"));
    }

    @Test
    void userWithoutAirportsCannotSignIn() throws Exception {
        user("no.airports", UserRole.OPERATIONS_VIEWER, true);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("no.airports", TEST_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingInvalidAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(get("/api/graph/network"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("authentication_required"));
        mvc.perform(get("/api/flights").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_invalid"));

        String foreign = new JwtUtil("some-other-secret-0123456789-abcdefghijkl", Duration.ofHours(1),
                Clock.systemUTC()).issue(principal(bomOps)).token();
        mvc.perform(get("/api/flights").header("Authorization", "Bearer " + foreign))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_invalid"));

        String expired = new JwtUtil(jwtSecret, Duration.ofHours(1),
                Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC)).issue(principal(bomOps)).token();
        mvc.perform(get("/api/graph/network").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_expired"));
    }

    @Test
    void disablingAUserRevokesTheirTokensImmediately() throws Exception {
        String token = bearer(bomOps);
        mvc.perform(get("/api/airports").header("Authorization", token)).andExpect(status().isOk());
        bomOps.setEnabled(false);
        appUserRepository.saveAndFlush(bomOps);
        mvc.perform(get("/api/airports").header("Authorization", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("account_inactive"));
    }

    @Test
    void healthIsPublicAndRevealsNothing() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    // ── Graph scope ──────────────────────────────────────────────────────────

    @Test
    void networkOnlyContainsFlightsOfTheUsersAirports() throws Exception {
        as(bomOps, get("/api/graph/network").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.flight_count").value(2))
                .andExpect(jsonPath("$.nodes[?(@.type == 'FLIGHT')].flight_number",
                        containsInAnyOrder("AI101", "SG8001")))
                .andExpect(jsonPath("$.nodes[?(@.type == 'AIRPORT')].iata_code",
                        containsInAnyOrder("BOM", "BLR", "DEL")))
                // AI101's dependencies lead to AI202 / AI505, which never touch BOM: not shown, not counted.
                .andExpect(jsonPath("$.summary.dependency_edge_count").value(0))
                .andExpect(jsonPath("$.summary.omitted_dependency_count").value(0));

        as(delOps, get("/api/graph/network").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.type == 'FLIGHT')].flight_number",
                        containsInAnyOrder("AI201", "AI202", "SG8001")))
                .andExpect(jsonPath("$.edges[?(@.category == 'DEPENDENCY')]", hasSize(1)))
                .andExpect(jsonPath("$.edges[?(@.category == 'DEPENDENCY')].relationship").value(hasItem("AIRCRAFT_ROTATION")));
    }

    @Test
    void airportsParameterCanNarrowButNeverWidenTheScope() throws Exception {
        as(bomOps, get("/api/graph/network").param("date", "2026-11-09").param("airports", "DEL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        as(bomOps, get("/api/graph/network").param("date", "2026-11-09").param("airports", "BOM,DEL"))
                .andExpect(status().isForbidden());
        as(bomOps, get("/api/graph/network").param("date", "2026-11-09").param("airports", "bom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.flight_count").value(2));
        as(bomOps, get("/api/graph/network").param("airports", "B1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userWithSeveralAirportsSeesExactlyThoseAirports() throws Exception {
        as(multiOps, get("/api/graph/network").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.type == 'FLIGHT')].flight_number",
                        containsInAnyOrder("AI101", "SG8001", "AI201", "AI202")));
        as(multiOps, get("/api/graph/network").param("date", "2026-11-09").param("airports", "DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.flight_count").value(3));
        as(multiOps, get("/api/graph/airports/BOM").param("date", "2026-11-09")).andExpect(status().isOk());
        as(multiOps, get("/api/graph/airports/DEL").param("date", "2026-11-09")).andExpect(status().isOk());
        as(multiOps, get("/api/graph/airports/BLR").param("date", "2026-11-09")).andExpect(status().isForbidden());
        as(multiOps, get("/api/airports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].iata_code", containsInAnyOrder("BOM", "DEL")));
    }

    @Test
    void anotherAirportsGraphAndOperationsAreForbidden() throws Exception {
        as(bomOps, get("/api/graph/airports/DEL").param("date", "2026-11-09"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        as(bomOps, get("/api/graph/airports/del")).andExpect(status().isForbidden());
        as(bomOps, get("/api/airports/DEL/operations").param("date", "2026-11-09")).andExpect(status().isForbidden());
        as(bomOps, get("/api/airports/BOM/operations").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airport.iata_code").value("BOM"));
        as(bomOps, get("/api/airports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].iata_code", containsInAnyOrder("BOM")));
    }

    @Test
    void flightsOfOtherAirportsCannotBeOpenedByEditingTheId() throws Exception {
        // AI505 BLR-HYD and AI202 BLR-DEL never touch BOM.
        for (String id : new String[]{sample.ai505().getFlightId().toString(), node(sample.ai202())}) {
            as(bomOps, get("/api/graph/flights/{id}", id)).andExpect(status().isForbidden());
            as(bomOps, get("/api/graph/flights/{id}/affected", id)).andExpect(status().isForbidden());
        }
        // SG8001 arrives at BOM: visible to BOM.
        as(bomOps, get("/api/graph/flights/{id}", sample.sg8001().getFlightId())).andExpect(status().isOk());
    }

    @Test
    void traversalStopsAtTheScopeBoundary() throws Exception {
        String ai202 = node(sample.ai202());
        String ai505 = node(sample.ai505());
        String ai506 = node(sample.ai506());
        as(bomOps, get("/api/graph/flights/{id}", sample.ai101().getFlightId()).param("depth", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traversal.downstream", empty()))
                .andExpect(jsonPath("$.traversal.upstream", empty()))
                .andExpect(jsonPath("$.nodes[*].id").value(not(hasItem(ai202))))
                .andExpect(jsonPath("$.nodes[*].id").value(not(hasItem(ai505))))
                .andExpect(jsonPath("$.nodes[*].id").value(not(hasItem(ai506))));
        as(bomOps, get("/api/graph/flights/{id}/affected", sample.ai101().getFlightId()).param("horizon_hours", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traversal.downstream", empty()));
        // DEL sees AI201 -> AI202 (both touch DEL) but not where AI202's passengers came from (AI101 BOM-BLR).
        as(delOps, get("/api/graph/flights/{id}", sample.ai202().getFlightId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traversal.upstream[*].flight_number", containsInAnyOrder("AI201")));
    }

    // ── Legacy flights and disruptions ───────────────────────────────────────

    @Test
    void legacyFlightsAreScopedAndOnlyTheDepartureAirportMayChangeThem() throws Exception {
        as(bomOps, get("/api/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].flight_id", containsInAnyOrder("AI-900")));
        as(bomOps, get("/api/flights").param("airport", "DEL")).andExpect(status().isForbidden());
        as(bomOps, get("/api/flights/AI-901")).andExpect(status().isForbidden());
        // DEL is AI-900's arrival airport: may view it, may not change it.
        as(delOps, get("/api/flights/AI-900")).andExpect(status().isOk());
        as(delOps, patch("/api/flights/AI-900/status").param("status", "CANCELLED")).andExpect(status().isForbidden());
        assertEquals(Flight.FlightStatus.SCHEDULED, legacyFlights.findByFlightId("AI-900").orElseThrow().getStatus());

        as(bomViewer, patch("/api/flights/AI-900/status").param("status", "DELAYED")).andExpect(status().isForbidden());
        as(bomOps, patch("/api/flights/AI-900/status").param("status", "DELAYED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELAYED"));
    }

    @Test
    void disruptionsAreScopedAndCannotBeTriggeredOnAnotherAirportsFlight() throws Exception {
        as(bomOps, get("/api/disruptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(bomDisruption.getId().intValue())));
        as(delOps, get("/api/disruptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(bomDisruption.getId().intValue(),
                        delDisruption.getId().intValue())));
        as(bomOps, get("/api/disruptions").param("airport", "DEL")).andExpect(status().isForbidden());

        long before = disruptions.count();
        as(bomOps, post("/api/disruptions/trigger").param("flightId", "AI-901").param("type", "GATE_CONFLICT")
                .param("delayMinutes", "90")).andExpect(status().isForbidden());
        assertEquals(before, disruptions.count());
        assertEquals(Flight.FlightStatus.SCHEDULED, legacyFlights.findByFlightId("AI-901").orElseThrow().getStatus());
    }

    // ── Resolution actions ───────────────────────────────────────────────────

    @Test
    void resolutionActionsOnAnotherAirportsDisruptionAreForbiddenAndChangeNothing() throws Exception {
        Long id = delDisruption.getId();
        String approveBody = "{\"gate_resolution\":{\"action\":\"REASSIGNED\",\"new_gate\":\"A1\"},"
                + "\"crew_resolution\":{\"action\":\"SWAPPED\",\"new_crew_id\":\"CR-901\"}}";

        as(bomOps, post("/api/agents/propose/{id}", id)).andExpect(status().isForbidden());
        as(bomOps, post("/api/agents/resolve/{id}", id)).andExpect(status().isForbidden());
        as(bomOps, post("/api/agents/approve/{id}", id).contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isForbidden());
        as(bomOps, post("/api/agents/reject/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"C1 closed\"}")).andExpect(status().isForbidden());
        // DEL is only the arrival airport of AI-900: it cannot act on BOM's disruption either.
        as(delOps, post("/api/agents/propose/{id}", bomDisruption.getId())).andExpect(status().isForbidden());

        Disruption unchanged = disruptions.findById(id).orElseThrow();
        assertEquals(DisruptionStatus.DETECTED, unchanged.getStatus());
        Flight ai901 = legacyFlights.findByFlightId("AI-901").orElseThrow();
        assertEquals("C1", ai901.getAssignedGate());
        assertEquals("CR-902", ai901.getAssignedCrewId());
        assertEquals(Flight.FlightStatus.SCHEDULED, ai901.getStatus());
    }

    @Test
    void readOnlyRoleCannotExecuteResolutions() throws Exception {
        as(bomViewer, post("/api/agents/propose/{id}", bomDisruption.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("read-only")));
        as(bomViewer, get("/api/disruptions")).andExpect(status().isOk());
    }

    @Test
    void unknownDisruptionIsNotFoundForAnOperator() throws Exception {
        as(bomOps, post("/api/agents/resolve/{id}", 999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void operatorOfTheDepartureAirportCanProposeAndApprove() throws Exception {
        Long id = bomDisruption.getId();
        as(bomOps, post("/api/agents/propose/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requires_approval").value(true))
                .andExpect(jsonPath("$.flight_id").value("AI-900"));
        assertEquals(DisruptionStatus.PROPOSED, disruptions.findById(id).orElseThrow().getStatus());

        // A hand-edited approval naming a gate that belongs to DEL must not move a BOM flight there.
        gates.save(Gate.builder().gateNumber("D9").terminal("T1").compatibleTypes("A320").available(true)
                .airportId(ReferenceAirports.airportUuid("DEL")).build());
        as(bomOps, post("/api/agents/approve/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gate_resolution\":{\"action\":\"REASSIGNED\",\"new_gate\":\"D9\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                // AI-900 continues to DEL, outside bom.ops' airports: no cascade there.
                .andExpect(jsonPath("$.cascade_result.flights_cascaded").value(0))
                .andExpect(jsonPath("$.cascade_result.cascade_scope_note").value(containsString("DEL")));

        assertEquals(DisruptionStatus.RESOLVED, disruptions.findById(id).orElseThrow().getStatus());
        assertEquals("A1", legacyFlights.findByFlightId("AI-900").orElseThrow().getAssignedGate());
        assertTrue(gates.findAll().stream().filter(g -> "D9".equals(g.getGateNumber())).allMatch(Gate::getAvailable));
        // AI-901 departs DEL: not disrupted by BOM's cascade.
        assertTrue(disruptions.findByFlightId("AI-901").stream().noneMatch(d -> d.getType() == DisruptionType.CASCADING_DELAY));
    }

    @Test
    void approvalByAMultiAirportOperatorCascadesOnlyIntoTheirAirports() throws Exception {
        as(multiOps, post("/api/agents/approve/{id}", bomDisruption.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gate_resolution\":{\"action\":\"KEEP\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascade_result.cascade_scope_note").doesNotExist());
        // AI-900 continues to DEL, which multi.ops operates: AI-901 (DEL-BLR) receives the cascade.
        List<Disruption> ai901 = disruptions.findByFlightId("AI-901");
        assertTrue(ai901.stream().anyMatch(d -> d.getType() == DisruptionType.CASCADING_DELAY));
        assertTrue(ai901.stream().map(Disruption::getFlightId).allMatch("AI-901"::equals));
    }

    @Test
    void protectedResponsesNeverIncludeDataOfOtherAirports() throws Exception {
        as(bomOps, get("/api/flights/disrupted")).andExpect(status().isOk());
        as(bomOps, get("/api/flights/risk/HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].origin", everyItem(oneOf("BOM", "DEL"))))
                .andExpect(jsonPath("$[*].flight_id", containsInAnyOrder("AI-900")));
        as(bomOps, get("/api/flights/weather/DEL")).andExpect(status().isForbidden());
    }

    private static AuthenticatedUser principal(AppUser user) {
        return new AuthenticatedUser(user.getUserId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                AirportScope.of(user.getAirports().stream().map(a -> a.getIataCode()).toList()));
    }
}
