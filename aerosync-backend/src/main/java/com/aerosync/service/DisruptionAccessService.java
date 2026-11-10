package com.aerosync.service;

import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.model.Disruption;
import com.aerosync.model.Flight;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Authorization of resolution actions (propose, resolve, approve, reject) on
 * a disruption: the user needs an operator role and must be authorized for
 * the departure airport of the disrupted flight. Disruption ids are
 * sequential, so this check is what stops a user from acting on another
 * airport's disruption by changing the id in the URL.
 */
@Service
public class DisruptionAccessService {

    private final DisruptionRepository disruptionRepository;
    private final FlightRepository flightRepository;

    public DisruptionAccessService(DisruptionRepository disruptionRepository, FlightRepository flightRepository) {
        this.disruptionRepository = disruptionRepository;
        this.flightRepository = flightRepository;
    }

    /**
     * @return the disruption when the user may act on it; empty when it does
     *         not exist (nothing to protect, callers keep their not-found answer)
     * @throws com.aerosync.exception.AccessForbiddenException read-only role, or
     *         the flight departs from an airport outside the user's scope (403)
     * @throws ResourceNotFoundException the disruption's flight no longer exists
     */
    @Transactional(readOnly = true)
    public Optional<Disruption> authorizeAction(Long disruptionId, AuthenticatedUser user) {
        user.requireOperatorRole();
        Optional<Disruption> disruption = disruptionRepository.findById(disruptionId);
        if (disruption.isEmpty()) {
            return Optional.empty();
        }
        Flight flight = flightRepository.findByFlightId(disruption.get().getFlightId())
                .orElseThrow(() -> new ResourceNotFoundException("Flight " + disruption.get().getFlightId()
                        + " of disruption " + disruptionId + " not found"));
        user.scope().requireOperate(flight.getOrigin(),
                "Disruption " + disruptionId + " (flight " + flight.getFlightId() + ")");
        return disruption;
    }
}
