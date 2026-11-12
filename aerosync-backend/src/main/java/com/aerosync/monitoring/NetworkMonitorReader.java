package com.aerosync.monitoring;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.graph.DependencyRecord;
import com.aerosync.graph.FlightRecord;
import com.aerosync.model.AircraftStatus;
import com.aerosync.model.FlightDependency;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.repository.FlightDependencyRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.service.OperationalRecordMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the operational network for the monitor in one read-only transaction
 * and runs {@link NetworkRules} on it. Only flights the configured data mode
 * serves are read, so a synthetic flight never raises a disruption in LIVE
 * mode.
 *
 * <p>The network is large (about 1,400 flights a day), so nothing is loaded
 * unless a flight is late or an aircraft is unavailable; then only the flights
 * reachable from those are read.</p>
 */
@Component
public class NetworkMonitorReader {

    private static final int CHUNK = 500;
    private static final List<FlightInstanceStatus> CANCELLED = List.of(FlightInstanceStatus.CANCELLED);
    private static final List<FlightInstanceStatus> PENDING = List.of(
            FlightInstanceStatus.SCHEDULED, FlightInstanceStatus.DELAYED, FlightInstanceStatus.BOARDING);
    private static final List<AircraftStatus> UNAVAILABLE = List.of(
            AircraftStatus.MAINTENANCE, AircraftStatus.GROUNDED);

    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightDependencyRepository flightDependencyRepository;
    private final DataSourcePolicy dataSourcePolicy;

    public NetworkMonitorReader(FlightInstanceRepository flightInstanceRepository,
                                FlightDependencyRepository flightDependencyRepository,
                                DataSourcePolicy dataSourcePolicy) {
        this.flightInstanceRepository = flightInstanceRepository;
        this.flightDependencyRepository = flightDependencyRepository;
        this.dataSourcePolicy = dataSourcePolicy;
    }

    @Transactional(readOnly = true)
    public List<Detection> detect(Instant now, MonitoringSettings settings) {
        Instant windowStart = now.minus(settings.networkLookback());
        Instant windowEnd = now.plus(settings.networkLookahead());

        List<FlightRecord> late = records(flightInstanceRepository.findLateFlights(
                windowStart, windowEnd, settings.networkMinDelay(), FlightInstanceStatus.DELAYED, CANCELLED));
        List<FlightRecord> unavailable = records(flightInstanceRepository.findFlightsWithUnavailableAircraft(
                now, windowEnd, PENDING, UNAVAILABLE));
        if (late.isEmpty() && unavailable.isEmpty()) {
            return List.of();
        }
        return NetworkRules.detect(late, unavailable, new NetworkRules.NetworkReader() {
            @Override
            public List<DependencyRecord> outgoing(Set<UUID> flightIds) {
                List<DependencyRecord> result = new ArrayList<>();
                for (List<UUID> chunk : chunks(flightIds)) {
                    for (FlightDependency dependency : flightDependencyRepository.findActiveOutgoing(chunk)) {
                        if (dataSourcePolicy.serves(dependency.getSourceFlight())
                                && dataSourcePolicy.serves(dependency.getTargetFlight())) {
                            result.add(OperationalRecordMapper.toRecord(dependency));
                        }
                    }
                }
                return result;
            }

            @Override
            public Map<UUID, FlightRecord> flights(Set<UUID> flightIds) {
                Map<UUID, FlightRecord> result = new LinkedHashMap<>();
                for (List<UUID> chunk : chunks(flightIds)) {
                    for (FlightInstance flight : flightInstanceRepository.findGraphFlightsByIds(chunk)) {
                        if (dataSourcePolicy.serves(flight)) {
                            result.put(flight.getFlightId(), OperationalRecordMapper.toRecord(flight));
                        }
                    }
                }
                return result;
            }
        }, now, settings);
    }

    private List<FlightRecord> records(List<FlightInstance> flights) {
        List<FlightRecord> records = new ArrayList<>(flights.size());
        for (FlightInstance flight : flights) {
            if (dataSourcePolicy.serves(flight)) {
                records.add(OperationalRecordMapper.toRecord(flight));
            }
        }
        return records;
    }

    private static List<List<UUID>> chunks(Set<UUID> ids) {
        List<UUID> all = new ArrayList<>(ids);
        List<List<UUID>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            chunks.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return chunks;
    }
}
