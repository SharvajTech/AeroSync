package com.aerosync.synthetic;

import com.aerosync.model.DependencyType;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Output of {@link SyntheticNetworkGenerator}. Everything except the reference airports is synthetic. */
public record SyntheticNetwork(
        LocalDate startDate,
        int days,
        long seed,
        List<ReferenceAirports.ReferenceAirport> airports,
        List<SyntheticAircraft> aircraft,
        List<SyntheticFlight> flights,
        List<SyntheticDependency> dependencies) {

    public SyntheticNetwork {
        airports = List.copyOf(airports);
        aircraft = List.copyOf(aircraft);
        flights = List.copyOf(flights);
        dependencies = List.copyOf(dependencies);
    }

    public Map<DependencyType, Integer> dependencyCounts() {
        Map<DependencyType, Integer> counts = new EnumMap<>(DependencyType.class);
        for (DependencyType type : DependencyType.values()) {
            counts.put(type, 0);
        }
        dependencies.forEach(d -> counts.merge(d.type(), 1, Integer::sum));
        return counts;
    }
}
