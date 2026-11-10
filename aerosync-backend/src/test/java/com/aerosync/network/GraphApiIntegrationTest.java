package com.aerosync.network;

import com.aerosync.model.AppUser;
import com.aerosync.model.UserRole;
import com.aerosync.security.JwtFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the graph and airport operations endpoints (snake_case JSON, error codes).
 *
 * <p>Since Stage 3 these endpoints need a token; requests run through the real
 * {@link JwtFilter} as a user assigned to every airport, so the expected
 * national-network contents are unchanged. Airport scoping itself is covered
 * by AirportScopedAccessIntegrationTest.</p>
 */
class GraphApiIntegrationTest extends OperationalNetworkTestSupport {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JwtFilter jwtFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        insertReferenceAirports();
        AppUser allAirports = user("all.airports", UserRole.OPERATIONS_VIEWER, true, allAirportCodes());
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(jwtFilter)
                .defaultRequest(get("/").header("Authorization", bearer(allAirports)))
                .build();
    }

    @Test
    void networkEndpointReturnsAirportAndFlightNodesWithRouteAndDependencyEdges() throws Exception {
        Sample sample = createSampleNetwork();
        mvc.perform(get("/api/graph/network").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("NATIONAL_NETWORK"))
                .andExpect(jsonPath("$.service_date").value("2026-11-09"))
                .andExpect(jsonPath("$.synthetic_data").value(false))
                .andExpect(jsonPath("$.data_window.first_service_date").value("2026-11-09"))
                .andExpect(jsonPath("$.data_window.last_service_date").value("2026-11-10"))
                .andExpect(jsonPath("$.summary.airport_count").value(15))
                .andExpect(jsonPath("$.summary.flight_count").value(6))
                .andExpect(jsonPath("$.summary.dependency_edges_by_type.AIRCRAFT_ROTATION").value(3))
                .andExpect(jsonPath("$.nodes[?(@.id == 'airport:BOM')].iata_code").value(hasItem("BOM")))
                .andExpect(jsonPath("$.nodes[?(@.id == 'airport:BOM')].type").value(hasItem("AIRPORT")))
                .andExpect(jsonPath("$.nodes[?(@.id == '" + node(sample.ai101()) + "')].flight_number").value(hasItem("AI101")))
                .andExpect(jsonPath("$.nodes[?(@.id == '" + node(sample.ai101()) + "')].operating_date").value(hasItem("2026-11-09")))
                .andExpect(jsonPath("$.nodes[?(@.id == '" + node(sample.ai101()) + "')].aircraft.registration").value(hasItem("VT-T01")))
                .andExpect(jsonPath("$.edges[?(@.id == 'departs_from:" + sample.ai101().getFlightId() + "')].source").value(hasItem("airport:BOM")))
                .andExpect(jsonPath("$.edges[?(@.relationship == 'PASSENGER_CONNECTION')].target").value(hasItem(node(sample.ai202()))))
                .andExpect(jsonPath("$.edges[?(@.relationship == 'PASSENGER_CONNECTION')].metadata.hub").value(hasItem("BLR")))
                .andExpect(jsonPath("$.traversal").doesNotExist());
    }

    @Test
    void networkWithoutDependenciesOnlyHasRouteEdges() throws Exception {
        createSampleNetwork();
        mvc.perform(get("/api/graph/network").param("date", "2026-11-09").param("include_dependencies", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.dependency_edge_count").value(0))
                .andExpect(jsonPath("$.edges[?(@.category == 'DEPENDENCY')]", hasSize(0)))
                .andExpect(jsonPath("$.edges", hasSize(12)));
    }

    @Test
    void airportEndpointIsCaseInsensitiveAndScopedToTheAirport() throws Exception {
        Sample sample = createSampleNetwork();
        mvc.perform(get("/api/graph/airports/blr").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("AIRPORT"))
                .andExpect(jsonPath("$.focus_node_id").value("airport:BLR"))
                .andExpect(jsonPath("$.summary.flight_count").value(5))
                .andExpect(jsonPath("$.nodes[*].id").value(not(hasItem(node(sample.sg8001())))));
    }

    @Test
    void flightEndpointAcceptsInstanceIdsAndNodeIds() throws Exception {
        Sample sample = createSampleNetwork();
        for (String id : new String[]{sample.ai101().getFlightId().toString(), node(sample.ai101())}) {
            mvc.perform(get("/api/graph/flights/{flightId}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.view").value("FLIGHT"))
                    .andExpect(jsonPath("$.focus_node_id").value(node(sample.ai101())))
                    .andExpect(jsonPath("$.traversal.max_depth").value(1))
                    .andExpect(jsonPath("$.traversal.upstream", hasSize(0)))
                    .andExpect(jsonPath("$.traversal.downstream[*].flight_number").value(hasItem("AI505")))
                    .andExpect(jsonPath("$.traversal.downstream[?(@.flight_number == 'AI202')].via_dependency_type")
                            .value(hasItem("PASSENGER_CONNECTION")));
        }
    }

    @Test
    void affectedEndpointTraversesDownstreamDependencies() throws Exception {
        Sample sample = createSampleNetwork();
        mvc.perform(get("/api/graph/flights/{flightId}/affected", sample.ai101().getFlightId())
                        .param("types", "aircraft_rotation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view").value("AFFECTED_FLIGHTS"))
                .andExpect(jsonPath("$.traversal.downstream[*].flight_number").value(hasItem("AI506")))
                .andExpect(jsonPath("$.traversal.downstream", hasSize(2)))
                .andExpect(jsonPath("$.traversal.upstream").doesNotExist())
                .andExpect(jsonPath("$.traversal.dependency_types", hasSize(1)));
    }

    @Test
    void returnsNotFoundForUnknownAirportsAndFlights() throws Exception {
        createSampleNetwork();
        mvc.perform(get("/api/graph/airports/XYZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value(containsString("XYZ")));
        mvc.perform(get("/api/graph/flights/{flightId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/airports/XYZ/operations"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsBadRequestForMalformedParameters() throws Exception {
        Sample sample = createSampleNetwork();
        mvc.perform(get("/api/graph/airports/B1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/graph/flights/AI101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("not unique across dates")));
        mvc.perform(get("/api/graph/network").param("date", "09-11-2026"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/graph/flights/{flightId}", sample.ai101().getFlightId()).param("depth", "9"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/graph/flights/{flightId}/affected", sample.ai101().getFlightId()).param("types", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("BOGUS")));
    }

    @Test
    void airportOperationsEndpoint() throws Exception {
        createSampleNetwork();
        mvc.perform(get("/api/airports/BLR/operations").param("date", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airport.iata_code").value("BLR"))
                .andExpect(jsonPath("$.airport.node_id").value("airport:BLR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.departures[0].flight_number").value("AI505"))
                .andExpect(jsonPath("$.departures[0].scheduled_time_local").value("2026-11-09T09:30:00+05:30"))
                .andExpect(jsonPath("$.arrivals[0].flight_number").value("AI101"))
                .andExpect(jsonPath("$.arrivals[0].counterpart_airport").value("BOM"))
                .andExpect(jsonPath("$.summary.departure_count").value(2))
                .andExpect(jsonPath("$.summary.arrival_count").value(3));
        mvc.perform(get("/api/airports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(15)))
                .andExpect(jsonPath("$[*].iata_code").value(hasItem("GAU")));
    }
}
