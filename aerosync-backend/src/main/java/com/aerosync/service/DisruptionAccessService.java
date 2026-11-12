package com.aerosync.service;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.exception.OperationNotAvailableException;
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
    private final DataSourcePolicy dataSourcePolicy;

    public DisruptionAccessService(DisruptionRepository disruptionRepository, FlightRepository flightRepository,
                                   DataSourcePolicy dataSourcePolicy) {
        this.disruptionRepository = disruptionRepository;
        this.flightRepository = flightRepository;
        this.dataSourcePolicy = dataSourcePolicy;
    }

    /**
     * @return the disruption when the user may act on it; empty when it does
     *         not exist (nothing to protect, callers keep their not-found answer)
     * @throws com.aerosync.exception.AccessForbiddenException read-only role, or
     *         the flight departs from an airport outside the user's scope (403)
     * @throws ResourceNotFoundException the disruption's flight no longer exists
     * @throws OperationNotAvailableException the disruption is advisory (409)
     */
    @Transactional(readOnly = true)
    public Optional<Disruption> authorizeAction(Long disruptionId, AuthenticatedUser user) {
        user.requireOperatorRole();
        Optional<Disruption> found = disruptionRepository.findById(disruptionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Disruption disruption = found.get();
        String label = "Disruption " + disruptionId + " (flight " + disruption.getFlightId() + ")";

        if (disruption.getFlightInstanceId() != null) {
            // Advisory disruption of an operational-network flight: the airport
            // stored at detection time decides who may know about it (403 first,
            // so other airports learn nothing), and there is nothing to execute.
            user.scope().requireOperate(disruption.getOriginAirport(), label);
            if (!dataSourcePolicy.serves(disruption.getDataOrigin())) {
                return Optional.empty();
            }
            throw new OperationNotAvailableException(label + " is advisory: it concerns an operational-network "
                    + "flight, for which AeroSync has no gate or crew data to build a resolution from");
        }

        Flight flight = flightRepository.findByFlightId(disruption.getFlightId())
                .orElseThrow(() -> new ResourceNotFoundException("Flight " + disruption.getFlightId()
                        + " of disruption " + disruptionId + " not found"));
        user.scope().requireOperate(flight.getOrigin(), label);
        if (!dataSourcePolicy.serves(flight)) {
            return Optional.empty();
        }
        return found;
    }
}
