package com.aerosync.service;

import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CascadeService {

    private final FlightRepository           flightRepository;
    private final DisruptionService          disruptionService;
    private final ResolutionAlgorithmService algorithmService;

    // Resolve cascading disruptions from origin flight
    // Finds all connected flights and resolves them algorithmically
    public Map<String, Object> resolveCascade(
            String originFlightId, int delayMinutes) {

        log.info("🔗 Cascade resolution from: {} delay: {}min",
                originFlightId, delayMinutes);

        Flight origin = flightRepository
                .findByFlightId(originFlightId)
                .orElseThrow(() -> new RuntimeException(
                        "Flight not found: " + originFlightId));

        // Find all flights that depart FROM origin's destination
        List<Flight> connected = flightRepository
                .findByOriginAndStatus(
                        origin.getDestination(),
                        Flight.FlightStatus.SCHEDULED);

        log.info("Found {} connected flights", connected.size());

        List<Map<String, Object>> cascadeResolutions = new ArrayList<>();
        int totalCost = 0;

        for (Flight flight : connected) {
            // Estimate propagated delay based on connection type
            // Tighter connection = more delay propagation
            int propagatedDelay = (int)(delayMinutes * 0.7);

            if (propagatedDelay <= 15) continue;

            // Trigger disruption
            Disruption cascade = disruptionService.triggerDisruption(
                    flight.getFlightId(),
                    Disruption.DisruptionType.CASCADING_DELAY,
                    propagatedDelay
            );

            // Resolve algorithmically
            Map<String, Object> resolution =
                    algorithmService.resolveAlgorithmically(
                            cascade, flight);

            resolution.put("cascaded_from", originFlightId);
            resolution.put("propagated_delay", propagatedDelay);
            cascadeResolutions.add(resolution);

            totalCost += propagatedDelay * 150;
            log.info("  ↳ Cascade resolved: {} delay:{}min",
                    flight.getFlightId(), propagatedDelay);
        }

        return Map.of(
                "origin_flight",       originFlightId,
                "initial_delay",       delayMinutes,
                "flights_cascaded",    cascadeResolutions.size(),
                "cascade_resolutions", cascadeResolutions,
                "total_cascade_cost",  totalCost
        );
    }
}