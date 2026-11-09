package com.aerosync.graph;

import com.aerosync.graph.DependencyTraversal.Direction;
import com.aerosync.graph.DependencyTraversal.Options;
import com.aerosync.graph.DependencyTraversal.Reach;
import com.aerosync.graph.DependencyTraversal.Result;
import com.aerosync.model.DependencyType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.aerosync.graph.SampleNetwork.DAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyTraversalTest {

    private final SampleNetwork sample = new SampleNetwork();

    /** In-memory loader over a fixed dependency list (the service uses one DB query per level). */
    private static DependencyTraversal.DependencyLoader loader(List<DependencyRecord> all, List<Set<UUID>> calls) {
        return (direction, frontier) -> {
            calls.add(frontier);
            return all.stream().filter(d -> frontier.contains(
                    direction == Direction.DOWNSTREAM ? d.sourceFlightId() : d.targetFlightId())).toList();
        };
    }

    private Result traverse(UUID start, Direction direction, Options options) {
        return DependencyTraversal.traverse(start, sample.ai101.scheduledDeparture(), direction, options,
                loader(sample.dependencies(), new ArrayList<>()));
    }

    @Test
    void downstreamDependenciesOfAi101() {
        Result result = traverse(sample.ai101.flightId(), Direction.DOWNSTREAM, Options.depth(5));

        Map<UUID, Integer> depth = result.reached().stream().collect(Collectors.toMap(Reach::flightId, Reach::depth));
        assertEquals(Map.of(sample.ai202.flightId(), 1, sample.ai505.flightId(), 1, sample.ai506.flightId(), 2), depth);
        Reach ai506 = result.reached().stream().filter(r -> r.flightId().equals(sample.ai506.flightId()))
                .findFirst().orElseThrow();
        assertEquals(sample.ai505.flightId(), ai506.parentFlightId());
        assertEquals(DependencyType.AIRCRAFT_ROTATION, ai506.via().type());
        assertEquals(0.93 * 0.9, ai506.pathImpactWeight(), 1e-9);
        assertEquals(3, result.traversedEdges().size());
        assertFalse(result.truncated());
        assertFalse(result.depthLimitReached());
    }

    @Test
    void upstreamDependenciesOfAi202() {
        Result result = DependencyTraversal.traverse(sample.ai202.flightId(), sample.ai202.scheduledDeparture(),
                Direction.UPSTREAM, Options.depth(3), loader(sample.dependencies(), new ArrayList<>()));

        Map<UUID, DependencyType> via = result.reached().stream()
                .collect(Collectors.toMap(Reach::flightId, r -> r.via().type()));
        assertEquals(Map.of(
                sample.ai101.flightId(), DependencyType.PASSENGER_CONNECTION,
                sample.ai201.flightId(), DependencyType.AIRCRAFT_ROTATION), via);
    }

    @Test
    void stopsAtMaxDepthAndReportsIt() {
        List<Set<UUID>> calls = new ArrayList<>();
        Result result = DependencyTraversal.traverse(sample.ai101.flightId(), null, Direction.DOWNSTREAM,
                Options.depth(1), loader(sample.dependencies(), calls));

        assertEquals(Set.of(sample.ai202.flightId(), sample.ai505.flightId()), result.reachedFlightIds());
        assertTrue(result.depthLimitReached());
        assertEquals(1, calls.size(), "one loader call per BFS level");
    }

    @Test
    void followsOnlyRequestedDependencyTypes() {
        Options rotationsOnly = new Options(5, EnumSet.of(DependencyType.AIRCRAFT_ROTATION), 0.0, null, 100);
        Result result = traverse(sample.ai101.flightId(), Direction.DOWNSTREAM, rotationsOnly);

        assertEquals(Set.of(sample.ai505.flightId(), sample.ai506.flightId()), result.reachedFlightIds());
    }

    @Test
    void ignoresDependenciesBelowTheMinimumImpactWeight() {
        Result result = traverse(sample.ai101.flightId(), Direction.DOWNSTREAM, new Options(5, null, 0.5, null, 100));

        assertEquals(Set.of(sample.ai505.flightId(), sample.ai506.flightId()), result.reachedFlightIds());
    }

    @Test
    void appliesTheTimeHorizonToReachedFlights() {
        // AI505 departs 2.5 h after AI101, AI506 4 h 40 min after, AI202 3.5 h after.
        Result result = traverse(sample.ai101.flightId(), Direction.DOWNSTREAM,
                new Options(5, null, 0.0, Duration.ofHours(3), 100));

        assertEquals(Set.of(sample.ai505.flightId()), result.reachedFlightIds());
    }

    @Test
    void visitsEachFlightOnceEvenWithCycles() {
        FlightRecord a = SampleNetwork.flight("6E1001", "BOM", "DEL", DAY, 6, 0, 120, null, null);
        FlightRecord b = SampleNetwork.flight("6E1002", "DEL", "BOM", DAY, 9, 0, 120, null, null);
        List<DependencyRecord> cyclic = List.of(
                SampleNetwork.dependency(a, b, DependencyType.OPERATIONAL_CONSTRAINT, 0.5, null, Map.of()),
                SampleNetwork.dependency(b, a, DependencyType.OPERATIONAL_CONSTRAINT, 0.5, null, Map.of()));

        Result result = DependencyTraversal.traverse(a.flightId(), null, Direction.DOWNSTREAM, Options.depth(10),
                loader(cyclic, new ArrayList<>()));

        assertEquals(List.of(b.flightId()), result.reached().stream().map(Reach::flightId).toList());
        assertEquals(2, result.traversedEdges().size(), "the edge back to the start is kept, the node is not revisited");
    }

    @Test
    void truncatesAtMaxFlights() {
        Result result = traverse(sample.ai101.flightId(), Direction.DOWNSTREAM, new Options(5, null, 0.0, null, 1));

        assertEquals(1, result.reached().size());
        assertTrue(result.truncated());
    }

    @Test
    void validatesOptions() {
        assertThrows(IllegalArgumentException.class, () -> Options.depth(0));
        assertThrows(IllegalArgumentException.class, () -> new Options(1, null, 1.5, null, 10));
        assertThrows(IllegalArgumentException.class, () -> new Options(1, null, 0.0, Duration.ZERO, 10));
        assertThrows(IllegalArgumentException.class, () -> new Options(1, null, 0.0, null, 0));
        assertEquals(EnumSet.allOf(DependencyType.class), Options.depth(1).types());
    }
}
