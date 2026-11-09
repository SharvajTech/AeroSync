package com.aerosync.graph;

import com.aerosync.model.DependencyType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Breadth-first traversal of flight dependencies, level by level.
 *
 * <p>Dependencies are loaded per BFS level through a {@link DependencyLoader},
 * so the caller decides how to fetch them (one query per level against the
 * database, or an in-memory list in tests). Each flight is visited once, which
 * makes cycles harmless. This is pure reachability over the dependency graph;
 * it does not simulate or predict delays.</p>
 */
public final class DependencyTraversal {

    public enum Direction {
        /** Follow dependencies backwards: flights the start flight depends on. */
        UPSTREAM,
        /** Follow dependencies forwards: flights that depend on the start flight. */
        DOWNSTREAM
    }

    /** Loads active dependencies whose source (DOWNSTREAM) or target (UPSTREAM) is in {@code frontier}. */
    @FunctionalInterface
    public interface DependencyLoader {
        List<DependencyRecord> load(Direction direction, Set<UUID> frontier);
    }

    /**
     * @param maxDepth        maximum number of dependency hops (>= 1)
     * @param types           dependency types to follow (empty = all)
     * @param minImpactWeight ignore dependencies weaker than this (0..1)
     * @param horizon         only reach flights departing within this duration
     *                        after (downstream) or before (upstream) the start
     *                        flight's scheduled departure; null = no limit
     * @param maxFlights      stop after reaching this many flights (>= 1)
     */
    public record Options(int maxDepth, Set<DependencyType> types, double minImpactWeight,
                          Duration horizon, int maxFlights) {

        public Options {
            if (maxDepth < 1) {
                throw new IllegalArgumentException("maxDepth must be at least 1");
            }
            if (Double.isNaN(minImpactWeight) || minImpactWeight < 0.0 || minImpactWeight > 1.0) {
                throw new IllegalArgumentException("minImpactWeight must be between 0 and 1");
            }
            if (horizon != null && (horizon.isNegative() || horizon.isZero())) {
                throw new IllegalArgumentException("horizon must be positive");
            }
            if (maxFlights < 1) {
                throw new IllegalArgumentException("maxFlights must be at least 1");
            }
            types = types == null || types.isEmpty()
                    ? EnumSet.allOf(DependencyType.class)
                    : EnumSet.copyOf(types);
        }

        public static Options depth(int maxDepth) {
            return new Options(maxDepth, null, 0.0, null, 5_000);
        }
    }

    /**
     * A reached flight.
     *
     * @param depth            hops from the start flight
     * @param parentFlightId   flight it was reached from
     * @param via              dependency that first reached it
     * @param pathImpactWeight product of impact weights along that path
     */
    public record Reach(UUID flightId, int depth, UUID parentFlightId, DependencyRecord via,
                        double pathImpactWeight) {
    }

    /**
     * @param reached           flights in BFS order (the start flight is not included)
     * @param traversedEdges    every followed dependency between visited flights
     * @param truncated         the maxFlights cap stopped the traversal
     * @param depthLimitReached flights at maxDepth were not expanded further
     */
    public record Result(UUID startFlightId, Direction direction, List<Reach> reached,
                         List<DependencyRecord> traversedEdges, boolean truncated, boolean depthLimitReached) {

        public Result {
            reached = List.copyOf(reached);
            traversedEdges = List.copyOf(traversedEdges);
        }

        public Set<UUID> reachedFlightIds() {
            Set<UUID> ids = new LinkedHashSet<>();
            reached.forEach(r -> ids.add(r.flightId()));
            return ids;
        }
    }

    private DependencyTraversal() {
    }

    public static Result traverse(UUID startFlightId, Instant startScheduledDeparture, Direction direction,
                                  Options options, DependencyLoader loader) {
        Objects.requireNonNull(startFlightId, "startFlightId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(loader, "loader");

        Map<UUID, Double> pathWeights = new HashMap<>();
        pathWeights.put(startFlightId, 1.0);
        List<Reach> reached = new ArrayList<>();
        Map<UUID, DependencyRecord> traversed = new LinkedHashMap<>();
        boolean truncated = false;

        Set<UUID> frontier = new LinkedHashSet<>();
        frontier.add(startFlightId);
        int depth = 0;
        while (!frontier.isEmpty() && depth < options.maxDepth() && !truncated) {
            depth++;
            List<DependencyRecord> candidates = new ArrayList<>(loader.load(direction, Set.copyOf(frontier)));
            candidates.sort(Comparator
                    .comparing((DependencyRecord d) -> farDeparture(d, direction),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(DependencyRecord::dependencyId));

            Set<UUID> next = new LinkedHashSet<>();
            for (DependencyRecord dependency : candidates) {
                UUID from = direction == Direction.DOWNSTREAM ? dependency.sourceFlightId() : dependency.targetFlightId();
                UUID to = direction == Direction.DOWNSTREAM ? dependency.targetFlightId() : dependency.sourceFlightId();
                if (!frontier.contains(from) || !follows(dependency, options)
                        || !withinHorizon(dependency, direction, startScheduledDeparture, options.horizon())) {
                    continue;
                }
                if (pathWeights.containsKey(to)) {
                    traversed.putIfAbsent(dependency.dependencyId(), dependency);
                    continue;
                }
                if (reached.size() >= options.maxFlights()) {
                    truncated = true;
                    break;
                }
                double weight = pathWeights.get(from) * dependency.impactWeight();
                pathWeights.put(to, weight);
                reached.add(new Reach(to, depth, from, dependency, weight));
                traversed.putIfAbsent(dependency.dependencyId(), dependency);
                next.add(to);
            }
            frontier = next;
        }
        boolean depthLimitReached = !truncated && !frontier.isEmpty() && depth >= options.maxDepth();
        return new Result(startFlightId, direction, reached, new ArrayList<>(traversed.values()),
                truncated, depthLimitReached);
    }

    private static boolean follows(DependencyRecord dependency, Options options) {
        return dependency.active()
                && options.types().contains(dependency.type())
                && dependency.impactWeight() >= options.minImpactWeight();
    }

    private static boolean withinHorizon(DependencyRecord dependency, Direction direction, Instant start,
                                         Duration horizon) {
        if (horizon == null || start == null) {
            return true;
        }
        Instant far = farDeparture(dependency, direction);
        if (far == null) {
            return true;
        }
        return direction == Direction.DOWNSTREAM
                ? !far.isAfter(start.plus(horizon))
                : !far.isBefore(start.minus(horizon));
    }

    private static Instant farDeparture(DependencyRecord dependency, Direction direction) {
        return direction == Direction.DOWNSTREAM
                ? dependency.targetScheduledDeparture()
                : dependency.sourceScheduledDeparture();
    }
}
