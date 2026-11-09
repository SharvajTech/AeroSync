package com.aerosync.controller;

import com.aerosync.dto.graph.GraphResponse;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.graph.NetworkIdentifiers;
import com.aerosync.service.OperationalGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only operational network graph (airports + flight instances +
 * dependencies). Separate from the GNN cascade-risk model.
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"})
public class GraphController {

    private final OperationalGraphService graphService;

    // GET /api/graph/network?date=2026-11-09&include_dependencies=true
    @GetMapping("/network")
    public ResponseEntity<GraphResponse> network(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "include_dependencies", defaultValue = "true") boolean includeDependencies) {
        return ResponseEntity.ok(graphService.getNationalNetwork(date, includeDependencies));
    }

    // GET /api/graph/airports/BOM?date=2026-11-09
    @GetMapping("/airports/{iata}")
    public ResponseEntity<GraphResponse> airport(
            @PathVariable("iata") String iata,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "include_dependencies", defaultValue = "true") boolean includeDependencies) {
        return ResponseEntity.ok(graphService.getAirportGraph(iata, date, includeDependencies));
    }

    // GET /api/graph/flights/{flightId}?depth=1   (flightId = instance UUID or "flight:<uuid>")
    @GetMapping("/flights/{flightId}")
    public ResponseEntity<GraphResponse> flight(
            @PathVariable("flightId") String flightId,
            @RequestParam(name = "depth", defaultValue = "1") int depth) {
        return ResponseEntity.ok(graphService.getFlightGraph(parseFlightId(flightId), depth));
    }

    // GET /api/graph/flights/{flightId}/affected?max_depth=6&horizon_hours=24&types=AIRCRAFT_ROTATION,CREW_ROTATION
    // Downstream dependency traversal only (reachability); horizon_hours=0 disables the time horizon.
    @GetMapping("/flights/{flightId}/affected")
    public ResponseEntity<GraphResponse> affected(
            @PathVariable("flightId") String flightId,
            @RequestParam(name = "max_depth", defaultValue = "6") int maxDepth,
            @RequestParam(name = "horizon_hours", defaultValue = "24") int horizonHours,
            @RequestParam(name = "types", required = false) String types,
            @RequestParam(name = "min_impact_weight", defaultValue = "0.0") double minImpactWeight,
            @RequestParam(name = "max_flights", defaultValue = "500") int maxFlights) {
        Integer horizon = horizonHours == 0 ? null : horizonHours;
        return ResponseEntity.ok(graphService.getAffectedFlights(parseFlightId(flightId), maxDepth, horizon,
                OperationalGraphService.parseDependencyTypes(types), minImpactWeight, maxFlights));
    }

    private static UUID parseFlightId(String flightId) {
        try {
            return NetworkIdentifiers.parseFlightReference(flightId);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestParameterException(ex.getMessage());
        }
    }
}
