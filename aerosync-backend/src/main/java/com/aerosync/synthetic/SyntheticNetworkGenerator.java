package com.aerosync.synthetic;

import com.aerosync.graph.DependencyRules;
import com.aerosync.model.DependencyType;
import com.aerosync.synthetic.ReferenceAirports.ReferenceAirport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic generator of a SYNTHETIC domestic operational network:
 * timetable, aircraft rotations and flight dependencies.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><b>Routes:</b> a fixed list of city pairs that are actually served
 *   (trunk routes between six hubs plus hub-to-spoke routes); airports are never
 *   connected all-to-all. Each route has a target daily frequency per
 *   direction.</li>
 *   <li><b>Lines of flying:</b> each aircraft flies one daily line that starts
 *   at its base between 05:30 and 07:30 local time and returns to the base by
 *   23:59, moving from station to station along served routes (so a line can
 *   be an out-and-back or a W/triangle pattern). The next leg is chosen by the
 *   remaining frequency deficit of each route, and only if the aircraft can
 *   still get home in time. Because every line starts and ends at its base,
 *   aircraft flows balance at every station and the same timetable repeats on
 *   every service date (the same flight number operates on many dates).</li>
 *   <li><b>Times:</b> block time from great-circle distance
 *   ({@code 35 min + distance / 750 km/h}, ATR72 at 480 km/h, rounded to 5
 *   minutes, at least 45 minutes); ground time = minimum turnaround for the
 *   type plus 0-30 minutes.</li>
 *   <li><b>Dependencies:</b> AIRCRAFT_ROTATION between consecutive legs of a
 *   tail, including the overnight link into the next day (gap up to 24 h);
 *   PASSENGER_CONNECTION at hubs between same-airline flights inside a
 *   minimum-connection window; CREW_ROTATION where a crew changes aircraft at a
 *   hub; OPERATIONAL_CONSTRAINT for consecutive departures sharing a
 *   ground-handling team at DEL/BOM/BLR. Every dependency is checked with
 *   {@link DependencyRules}.</li>
 * </ul>
 *
 * <p>Only planned data is generated: every instance is SCHEDULED with no
 * estimated or actual times and no delay, i.e. no real-time data is invented.
 * Airline designators follow the existing AeroSync demo data (AI, 6E, ...),
 * but flight numbers, timings, tails and dependencies are synthetic.
 * Registrations contain a digit (e.g. VT-A07) so they can never match a real
 * Indian registration (VT- plus three letters).</p>
 *
 * <p>The demonstration chain {@code BOM -> AI101 -> BLR -> AI202 -> DEL} is
 * pinned every day: AI101 has a passenger connection onto AI202 and its
 * aircraft continues separately on AI505 (BLR-HYD).</p>
 */
public final class SyntheticNetworkGenerator {

    public static final int DEFAULT_DAYS = 7;
    public static final long DEFAULT_SEED = 20_261_109L;

    static final int FIRST_DEPARTURE_MINUTE = 5 * 60 + 30;   // 05:30 local
    static final int LATEST_LINE_START_MINUTE = 7 * 60 + 30; // 07:30 local
    static final int LAST_DEPARTURE_MINUTE  = 22 * 60 + 45;  // 22:45 local
    static final int LAST_ARRIVAL_MINUTE    = 23 * 60 + 59;  // 23:59 local, back at base
    static final int MAX_EXTRA_GROUND_MINUTES = 30;

    static final List<String> HUBS = List.of("DEL", "BOM", "BLR", "HYD", "MAA", "CCU");
    static final Map<String, Integer> HUB_MINIMUM_CONNECTION_MINUTES = Map.of(
            "DEL", 60, "BOM", 60, "BLR", 45, "HYD", 45, "MAA", 45, "CCU", 45);
    static final List<String> GROUND_HANDLING_STATIONS = List.of("DEL", "BOM", "BLR");

    static final int PASSENGER_CONNECTION_WINDOW_MINUTES = 240;
    static final int CREW_MINIMUM_CONNECTION_MINUTES = 40;
    static final int CREW_MAXIMUM_CONNECTION_MINUTES = 180;
    static final int ATR_MAXIMUM_DISTANCE_KM = 450;

    /**
     * A served city pair with its minimum daily departures in each direction.
     * Lines of flying keep adding legs until every minimum is met, so the
     * actual frequency is usually somewhat higher.
     */
    record Route(String a, String b, int dailyEachWay) {
    }

    static final List<Route> ROUTES = List.of(
            // Trunk routes between the six hubs
            new Route("DEL", "BOM", 13), new Route("DEL", "BLR", 10), new Route("BOM", "BLR", 8),
            new Route("DEL", "HYD", 6), new Route("BOM", "HYD", 5), new Route("BLR", "HYD", 5),
            new Route("DEL", "MAA", 5), new Route("BOM", "MAA", 5), new Route("BLR", "MAA", 3),
            new Route("DEL", "CCU", 6), new Route("BOM", "CCU", 4), new Route("BLR", "CCU", 4),
            new Route("HYD", "MAA", 2), new Route("HYD", "CCU", 2), new Route("MAA", "CCU", 2),
            // Hub-to-spoke routes (includes every route of the GNN training data)
            new Route("DEL", "AMD", 4), new Route("BOM", "AMD", 2), new Route("BLR", "AMD", 2),
            new Route("HYD", "AMD", 2),
            new Route("DEL", "GOI", 3), new Route("BOM", "GOI", 3), new Route("BLR", "GOI", 2),
            new Route("HYD", "GOI", 1),
            new Route("BOM", "PNQ", 1), new Route("DEL", "PNQ", 4), new Route("BLR", "PNQ", 3),
            new Route("HYD", "PNQ", 2),
            new Route("DEL", "COK", 2), new Route("BOM", "COK", 3), new Route("BLR", "COK", 3),
            new Route("MAA", "COK", 2),
            new Route("DEL", "JAI", 2), new Route("BOM", "JAI", 2), new Route("BLR", "JAI", 2),
            new Route("DEL", "LKO", 3), new Route("BOM", "LKO", 2), new Route("BLR", "LKO", 2),
            new Route("DEL", "GAU", 2), new Route("CCU", "GAU", 3), new Route("BLR", "GAU", 2),
            new Route("DEL", "NAG", 2), new Route("BOM", "NAG", 2), new Route("BLR", "NAG", 1),
            new Route("HYD", "NAG", 1),
            new Route("DEL", "BBI", 2), new Route("CCU", "BBI", 2), new Route("BLR", "BBI", 2));

    /** Airline profile: market share used for slot allocation, first flight number, registration letter. */
    record Airline(String code, double share, int firstFlightNumber, char registrationLetter) {
    }

    static final List<Airline> AIRLINES = List.of(
            new Airline("6E", 0.55, 2001, 'I'),
            new Airline("AI", 0.25, 401, 'A'),
            new Airline("IX", 0.08, 1501, 'X'),
            new Airline("QP", 0.07, 1101, 'Q'),
            new Airline("SG", 0.05, 8001, 'S'));

    /** A pinned leg of a demonstration line (all AI A320). */
    record AnchorLeg(String flightNumber, String destination, LocalTime departure) {
    }

    /**
     * Demonstration lines: BOM -> AI101 -> BLR -> AI202 -> DEL. AI101's aircraft
     * (BOM line) continues on AI505 to HYD, while AI202 is flown by the DEL
     * line's aircraft, so AI101 -> AI202 is a pure passenger connection and
     * AI101 -> AI505 a separate aircraft rotation.
     */
    static final Map<String, List<AnchorLeg>> ANCHOR_LINES = Map.of(
            "BOM", List.of(
                    new AnchorLeg("AI101", "BLR", LocalTime.of(7, 0)),
                    new AnchorLeg("AI505", "HYD", LocalTime.of(9, 30)),
                    new AnchorLeg("AI506", "BLR", LocalTime.of(11, 40)),
                    new AnchorLeg("AI102", "BOM", LocalTime.of(13, 45))),
            "DEL", List.of(
                    new AnchorLeg("AI201", "BLR", LocalTime.of(6, 15)),
                    new AnchorLeg("AI202", "DEL", LocalTime.of(10, 30))));

    static final String ANCHOR_CONNECTION_SOURCE = "AI101";
    static final String ANCHOR_CONNECTION_TARGET = "AI202";

    private SyntheticNetworkGenerator() {
    }

    public static SyntheticNetwork generate(LocalDate startDate, int days, long seed) {
        Objects.requireNonNull(startDate, "startDate");
        if (days < 1 || days > 31) {
            throw new IllegalArgumentException("days must be between 1 and 31, was " + days);
        }
        return new Run(startDate, days, seed).generate();
    }

    public static int minimumTurnaroundMinutes(String aircraftType) {
        return switch (aircraftType) {
            case "ATR72" -> 20;
            case "A320", "B737" -> 30;
            case "A321" -> 35;
            case "B777" -> 70;
            default -> 45;
        };
    }

    public static int blockMinutes(double distanceKm, String aircraftType) {
        double kmPerMinute = "ATR72".equals(aircraftType) ? 8.0 : 12.5;
        int minutes = (int) (Math.round((35 + distanceKm / kmPerMinute) / 5.0) * 5);
        return Math.max(45, minutes);
    }

    static UUID syntheticUuid(String kind, String key) {
        return UUID.nameUUIDFromBytes(("aerosync:synthetic:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    // ── One generation run (keeps the random stream and intermediate state) ─

    private static final class Run {

        private final LocalDate startDate;
        private final int days;
        private final long seed;
        private final Random random;

        private final List<Draft> drafts = new ArrayList<>();
        private final List<Tail> tails = new ArrayList<>();
        private final Map<String, Integer> registrationCounters = new HashMap<>();
        private final Map<String, Dependency> dependencies = new LinkedHashMap<>();

        Run(LocalDate startDate, int days, long seed) {
            this.startDate = startDate;
            this.days = days;
            this.seed = seed;
            this.random = new Random(seed);
        }

        SyntheticNetwork generate() {
            buildLinesOfFlying();
            expandOverServiceDates();
            addAircraftRotations();
            addPassengerConnections();
            addCrewRotations();
            addOperationalConstraints();
            return assemble();
        }

        // ── Timetable: daily lines of flying ──────────────────────────────

        private final List<Line> lines = new ArrayList<>();
        private final Map<String, Integer> nextFlightNumber = new HashMap<>();
        private final Set<String> reservedFlightNumbers = new HashSet<>();
        private final Map<String, Map<String, Integer>> minutesHomeCache = new HashMap<>();

        private void buildLinesOfFlying() {
            AIRLINES.forEach(a -> nextFlightNumber.put(a.code(), a.firstFlightNumber()));
            ANCHOR_LINES.values().forEach(legs -> legs.forEach(l -> reservedFlightNumbers.add(l.flightNumber())));
            Map<String, Integer> deficit = new LinkedHashMap<>();
            for (Route route : ROUTES) {
                deficit.put(directed(route.a(), route.b()), route.dailyEachWay());
                deficit.put(directed(route.b(), route.a()), route.dailyEachWay());
            }

            for (String base : List.of("BOM", "DEL")) {           // fixed iteration order
                Line line = new Line(newTail("AI", "A320", base), base);
                for (AnchorLeg leg : ANCHOR_LINES.get(base)) {
                    int departure = leg.departure().getHour() * 60 + leg.departure().getMinute();
                    if (departure < line.readyMinute) {
                        throw new IllegalStateException("Anchor " + leg.flightNumber() + " departs before its aircraft is ready");
                    }
                    addLeg(line, leg.flightNumber(), leg.destination(), departure, deficit);
                }
                continueLine(line, deficit);
                lines.add(line);
            }

            int attempts = 0;
            while (totalDeficit(deficit) > 0 && attempts++ < 2_000) {
                String base = pickBase(deficit);
                Airline airline = pickAirline();
                String type = pickAircraftType(airline, base);
                Line line = new Line(newTail(airline.code(), type, base), base);
                line.readyMinute = FIRST_DEPARTURE_MINUTE                              // 05:30-07:30
                        + 5 * random.nextInt((LATEST_LINE_START_MINUTE - FIRST_DEPARTURE_MINUTE) / 5 + 1);
                continueLine(line, deficit);
                if (line.legs.isEmpty()) {
                    throw new IllegalStateException("Line from " + base + " could not be scheduled");
                }
                lines.add(line);
            }
        }

        /** Extends a line leg by leg until it cannot leave its base again (it always ends at the base). */
        private void continueLine(Line line, Map<String, Integer> deficit) {
            String type = line.tail.aircraftType;
            while (true) {
                int departure = roundUpTo5(line.readyMinute) + 5 * random.nextInt(MAX_EXTRA_GROUND_MINUTES / 5 + 1);
                List<String> feasible = new ArrayList<>();
                for (String next : neighbours(line.station, type)) {
                    if (canFlyAndReturn(line, next, departure)) {
                        feasible.add(next);
                    }
                }
                if (feasible.isEmpty()) {
                    if (line.station.equals(line.base)) {
                        return;
                    }
                    // Guaranteed by canFlyAndReturn's conservative estimate; fly the quickest leg home.
                    String home = quickestStepHome(line.station, line.base, type);
                    addLeg(line, null, home, roundUpTo5(line.readyMinute), deficit);
                    continue;
                }
                String next = pickByDeficit(line.station, feasible, deficit);
                addLeg(line, null, next, departure, deficit);
            }
        }

        private boolean canFlyAndReturn(Line line, String next, int departure) {
            String type = line.tail.aircraftType;
            if (departure > LAST_DEPARTURE_MINUTE) {
                return false;
            }
            int arrival = departure + blockMinutes(distanceKm(line.station, next), type);
            if (next.equals(line.base)) {
                return arrival <= LAST_ARRIVAL_MINUTE;
            }
            Integer home = minutesHome(line.base, type).get(next);
            return home != null
                    && arrival + minimumTurnaroundMinutes(type) + MAX_EXTRA_GROUND_MINUTES + home <= LAST_ARRIVAL_MINUTE;
        }

        private void addLeg(Line line, String flightNumber, String destination, int departureMinute,
                            Map<String, Integer> deficit) {
            String type = line.tail.aircraftType;
            int block = blockMinutes(distanceKm(line.station, destination), type);
            String number = flightNumber != null ? flightNumber : allocateFlightNumber(line.tail.airline);
            line.legs.add(new PatternFlight(number, line.tail.airline, line.station, destination,
                    departureMinute, block, type));
            deficit.computeIfPresent(directed(line.station, destination), (k, v) -> v - 1);
            line.station = destination;
            line.readyMinute = departureMinute + block + minimumTurnaroundMinutes(type);
        }

        private String allocateFlightNumber(String airline) {
            int number = nextFlightNumber.get(airline);
            while (reservedFlightNumbers.contains(airline + number)) {
                number++;
            }
            if (number > 9999) {
                throw new IllegalStateException("Flight numbers exhausted for " + airline);
            }
            nextFlightNumber.put(airline, number + 1);
            return airline + number;
        }

        private String pickBase(Map<String, Integer> deficit) {
            Map<String, Integer> outgoing = new LinkedHashMap<>();
            deficit.forEach((route, remaining) -> {
                if (remaining > 0) {
                    outgoing.merge(route.substring(0, 3), remaining, Integer::sum);
                }
            });
            return weightedChoice(outgoing);
        }

        private String pickByDeficit(String from, List<String> candidates, Map<String, Integer> deficit) {
            Map<String, Double> weights = new LinkedHashMap<>();
            for (String to : candidates) {
                int remaining = deficit.getOrDefault(directed(from, to), 0);
                weights.put(to, remaining > 0 ? remaining : 0.15);
            }
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            double roll = random.nextDouble() * total;
            for (Map.Entry<String, Double> entry : weights.entrySet()) {
                roll -= entry.getValue();
                if (roll < 0) {
                    return entry.getKey();
                }
            }
            return candidates.get(candidates.size() - 1);
        }

        private String weightedChoice(Map<String, Integer> weights) {
            int total = weights.values().stream().mapToInt(Integer::intValue).sum();
            int roll = random.nextInt(total);
            for (Map.Entry<String, Integer> entry : weights.entrySet()) {
                roll -= entry.getValue();
                if (roll < 0) {
                    return entry.getKey();
                }
            }
            throw new IllegalStateException("empty weights");
        }

        private static int totalDeficit(Map<String, Integer> deficit) {
            return deficit.values().stream().mapToInt(v -> Math.max(0, v)).sum();
        }

        /** Stations reachable in one leg with this aircraft type. */
        private static List<String> neighbours(String station, String type) {
            List<String> result = new ArrayList<>();
            for (Route route : ROUTES) {
                String other = route.a().equals(station) ? route.b() : route.b().equals(station) ? route.a() : null;
                if (other != null && canOperate(type, route)) {
                    result.add(other);
                }
            }
            return result;
        }

        private static boolean canOperate(String type, Route route) {
            return switch (type) {
                case "ATR72" -> distanceKm(route.a(), route.b()) < ATR_MAXIMUM_DISTANCE_KM;
                case "B777" -> isPair(route, "DEL", "BOM") || isPair(route, "DEL", "BLR") || isPair(route, "BOM", "BLR");
                default -> true;
            };
        }

        /** Conservative minutes (block + turnaround + maximum extra ground) to fly from each station to {@code base}. */
        private Map<String, Integer> minutesHome(String base, String type) {
            return minutesHomeCache.computeIfAbsent(base + "/" + type, key -> {
                Map<String, Integer> best = new HashMap<>();
                best.put(base, 0);
                List<String> queue = new ArrayList<>(List.of(base));
                while (!queue.isEmpty()) {                    // Bellman-Ford style relaxation; graph is tiny
                    List<String> nextQueue = new ArrayList<>();
                    for (String station : queue) {
                        for (String neighbour : neighbours(station, type)) {
                            int cost = best.get(station) + blockMinutes(distanceKm(neighbour, station), type)
                                    + (station.equals(base) ? 0 : minimumTurnaroundMinutes(type) + MAX_EXTRA_GROUND_MINUTES);
                            if (cost < best.getOrDefault(neighbour, Integer.MAX_VALUE)) {
                                best.put(neighbour, cost);
                                nextQueue.add(neighbour);
                            }
                        }
                    }
                    queue = nextQueue;
                }
                return best;
            });
        }

        private String quickestStepHome(String station, String base, String type) {
            Map<String, Integer> home = minutesHome(base, type);
            String bestStep = null;
            int bestCost = Integer.MAX_VALUE;
            for (String neighbour : neighbours(station, type)) {
                Integer remaining = home.get(neighbour);
                if (remaining == null) {
                    continue;
                }
                int cost = blockMinutes(distanceKm(station, neighbour), type) + remaining;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestStep = neighbour;
                }
            }
            if (bestStep == null) {
                throw new IllegalStateException("No way home from " + station + " to " + base + " for " + type);
            }
            return bestStep;
        }

        private Airline pickAirline() {
            double roll = random.nextDouble();
            double cumulative = 0;
            for (Airline airline : AIRLINES) {
                cumulative += airline.share();
                if (roll < cumulative) {
                    return airline;
                }
            }
            return AIRLINES.get(AIRLINES.size() - 1);
        }

        private String pickAircraftType(Airline airline, String base) {
            double roll = random.nextDouble();
            return switch (airline.code()) {
                case "6E" -> {
                    if (roll < 0.12 && !neighbours(base, "ATR72").isEmpty()) {
                        yield "ATR72";
                    }
                    yield random.nextDouble() < 0.7 ? "A320" : "A321";
                }
                case "AI" -> {
                    if (roll < 0.08 && !neighbours(base, "B777").isEmpty()) {
                        yield "B777";
                    }
                    yield random.nextDouble() < 0.6 ? "A320" : "A321";
                }
                default -> "B737";
            };
        }

        private void expandOverServiceDates() {
            for (int day = 0; day < days; day++) {
                LocalDate serviceDate = startDate.plusDays(day);
                for (Line line : lines) {
                    for (PatternFlight p : line.legs) {
                        ZoneId originZone = ZoneId.of(ReferenceAirports.require(p.origin()).timezone());
                        Instant departure = ZonedDateTime.of(serviceDate,
                                LocalTime.of(p.departureMinute() / 60, p.departureMinute() % 60), originZone)
                                .toInstant();
                        Instant arrival = departure.plus(Duration.ofMinutes(p.blockMinutes()));
                        String legKey = p.flightNumber() + ":" + serviceDate + ":" + p.origin();
                        Draft draft = new Draft(
                                syntheticUuid("flight", legKey),
                                syntheticUuid("schedule", legKey),
                                p.flightNumber(), p.airline(), p.origin(), p.destination(), serviceDate,
                                departure, arrival, p.aircraftType());
                        draft.tail = line.tail;
                        line.tail.legs.add(draft);
                        drafts.add(draft);
                    }
                }
            }
            drafts.sort(Comparator.comparing((Draft d) -> d.departure)
                    .thenComparing(d -> d.flightNumber)
                    .thenComparing(d -> d.origin));
        }

        private static String directed(String from, String to) {
            return from + ">" + to;
        }

        private static int roundUpTo5(int minutes) {
            return ((minutes + 4) / 5) * 5;
        }

        private Tail newTail(String airline, String aircraftType, String station) {
            char letter = AIRLINES.stream().filter(a -> a.code().equals(airline)).findFirst()
                    .orElseThrow().registrationLetter();
            int n = registrationCounters.merge(airline, 1, Integer::sum);
            String registration = "VT-" + letter + registrationSuffix(n);
            Tail tail = new Tail(syntheticUuid("aircraft", registration), registration, aircraftType, airline, station);
            tails.add(tail);
            return tail;
        }

        /** 01..99, then A0..Z9: always contains a digit. */
        private static String registrationSuffix(int n) {
            if (n <= 99) {
                return String.format("%02d", n);
            }
            int index = n - 100;
            if (index >= 26 * 10) {
                throw new IllegalStateException("Too many synthetic aircraft for one airline");
            }
            return "" + (char) ('A' + index / 10) + (index % 10);
        }

        private void addAircraftRotations() {
            for (Tail tail : tails) {
                for (int i = 0; i + 1 < tail.legs.size(); i++) {
                    Draft source = tail.legs.get(i);
                    Draft target = tail.legs.get(i + 1);
                    long ground = minutesBetween(source.arrival, target.departure);
                    if (ground > DependencyRules.MAX_CONNECTION_GAP.toMinutes()) {
                        continue;
                    }
                    int minimumTurn = minimumTurnaroundMinutes(tail.aircraftType);
                    long slack = ground - minimumTurn;
                    double weight = round3(clamp(1.0 - slack / 300.0, 0.2, 1.0));
                    boolean overnight = !localDate(source.arrival, source.destination)
                            .equals(localDate(target.departure, target.origin)) || ground >= 6 * 60;
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("synthetic", true);
                    metadata.put("aircraft_registration", tail.registration);
                    metadata.put("aircraft_type", tail.aircraftType);
                    metadata.put("station", source.destination);
                    metadata.put("scheduled_ground_minutes", ground);
                    metadata.put("minimum_turnaround_minutes", minimumTurn);
                    metadata.put("slack_minutes", slack);
                    metadata.put("overnight", overnight);
                    metadata.put("crew_continuity", true);
                    addDependency(source, target, DependencyType.AIRCRAFT_ROTATION, weight, minimumTurn, metadata);
                }
            }
        }

        // ── Passenger connections ─────────────────────────────────────────

        private void addPassengerConnections() {
            Map<String, List<Draft>> departures = departuresByStation();
            for (String hub : HUBS) {
                int mct = HUB_MINIMUM_CONNECTION_MINUTES.get(hub);
                List<Draft> hubDepartures = departures.getOrDefault(hub, List.of());
                for (Draft arrival : arrivalsAt(hub)) {
                    if (random.nextDouble() >= 0.65) {
                        continue;
                    }
                    List<Draft> candidates = new ArrayList<>();
                    for (Draft departure : hubDepartures) {
                        long gap = minutesBetween(arrival.arrival, departure.departure);
                        if (gap > mct + PASSENGER_CONNECTION_WINDOW_MINUTES) {
                            break;
                        }
                        if (gap >= mct && departure.airline.equals(arrival.airline)
                                && !departure.destination.equals(arrival.origin)) {
                            candidates.add(departure);
                        }
                    }
                    Collections.shuffle(candidates, random);
                    int wanted = 1 + random.nextInt(2);
                    for (Draft departure : candidates.subList(0, Math.min(wanted, candidates.size()))) {
                        int passengers = 4 + random.nextInt(45);
                        addPassengerConnection(hub, mct, arrival, departure, passengers);
                    }
                }
            }
            // Demonstration chain: AI101 (BOM-BLR) passengers connect onto AI202 (BLR-DEL) every day.
            Map<String, Draft> byNumberAndDate = new HashMap<>();
            drafts.forEach(d -> byNumberAndDate.put(d.flightNumber + "|" + d.serviceDate, d));
            for (int day = 0; day < days; day++) {
                LocalDate serviceDate = startDate.plusDays(day);
                Draft source = byNumberAndDate.get(ANCHOR_CONNECTION_SOURCE + "|" + serviceDate);
                Draft target = byNumberAndDate.get(ANCHOR_CONNECTION_TARGET + "|" + serviceDate);
                String hub = source.destination;
                if (!dependencies.containsKey(key(source, target, DependencyType.PASSENGER_CONNECTION))) {
                    addPassengerConnection(hub, HUB_MINIMUM_CONNECTION_MINUTES.get(hub), source, target, 26);
                }
            }
        }

        private void addPassengerConnection(String hub, int mct, Draft arrival, Draft departure, int passengers) {
            long gap = minutesBetween(arrival.arrival, departure.departure);
            double tightness = 1.0 - (double) (gap - mct) / PASSENGER_CONNECTION_WINDOW_MINUTES;
            double weight = round3(clamp(0.15 + 0.35 * passengers / 48.0 + 0.15 * tightness, 0.0, 1.0));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("synthetic", true);
            metadata.put("hub", hub);
            metadata.put("connecting_passengers", passengers);
            metadata.put("scheduled_connection_minutes", gap);
            metadata.put("minimum_connection_minutes", mct);
            addDependency(arrival, departure, DependencyType.PASSENGER_CONNECTION, weight, mct, metadata);
        }

        // ── Crew rotations ────────────────────────────────────────────────

        private void addCrewRotations() {
            Map<String, List<Draft>> departures = departuresByStation();
            Set<UUID> crewedTargets = new HashSet<>();
            Map<String, Integer> pairingCounters = new HashMap<>();
            for (String hub : HUBS) {
                List<Draft> hubDepartures = departures.getOrDefault(hub, List.of());
                for (Draft arrival : arrivalsAt(hub)) {
                    if (random.nextDouble() >= 0.12) {
                        continue;
                    }
                    Draft next = null;
                    for (Draft departure : hubDepartures) {
                        long gap = minutesBetween(arrival.arrival, departure.departure);
                        if (gap > CREW_MAXIMUM_CONNECTION_MINUTES) {
                            break;
                        }
                        if (gap >= CREW_MINIMUM_CONNECTION_MINUTES
                                && departure.airline.equals(arrival.airline)
                                && departure.aircraftType.equals(arrival.aircraftType)
                                && departure.tail != arrival.tail
                                && !crewedTargets.contains(departure.flightId)) {
                            next = departure;
                            break;
                        }
                    }
                    if (next == null) {
                        continue;
                    }
                    crewedTargets.add(next.flightId);
                    int pairing = pairingCounters.merge(arrival.airline, 1, Integer::sum);
                    String[] complements = {"FLIGHT_DECK", "CABIN", "FULL_CREW"};
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("synthetic", true);
                    metadata.put("station", hub);
                    metadata.put("crew_pairing", String.format("%s-CP-%04d", arrival.airline, pairing));
                    metadata.put("crew_complement", complements[random.nextInt(complements.length)]);
                    metadata.put("scheduled_connection_minutes", minutesBetween(arrival.arrival, next.departure));
                    double weight = round3(0.6 + 0.25 * random.nextDouble());
                    addDependency(arrival, next, DependencyType.CREW_ROTATION, weight,
                            CREW_MINIMUM_CONNECTION_MINUTES, metadata);
                    // The inbound crew left the aircraft, so its rotation continues with a different crew.
                    Draft ownNextLeg = nextLegOf(arrival);
                    if (ownNextLeg != null) {
                        Dependency rotation = dependencies.get(key(arrival, ownNextLeg, DependencyType.AIRCRAFT_ROTATION));
                        if (rotation != null) {
                            rotation.metadata.put("crew_continuity", false);
                        }
                    }
                }
            }
        }

        // ── Operational constraints ───────────────────────────────────────

        private void addOperationalConstraints() {
            Map<String, List<Draft>> departures = departuresByStation();
            for (String station : GROUND_HANDLING_STATIONS) {
                Map<String, Draft> previousByAirline = new HashMap<>();
                for (Draft departure : departures.getOrDefault(station, List.of())) {
                    Draft previous = previousByAirline.put(departure.airline, departure);
                    if (previous == null) {
                        continue;
                    }
                    long separation = minutesBetween(previous.departure, departure.departure);
                    if (separation >= 5 && separation <= 20 && random.nextDouble() < 0.2) {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("synthetic", true);
                        metadata.put("constraint_type", "SHARED_GROUND_HANDLING_TEAM");
                        metadata.put("station", station);
                        metadata.put("scheduled_separation_minutes", separation);
                        double weight = round3(0.25 + 0.15 * random.nextDouble());
                        addDependency(previous, departure, DependencyType.OPERATIONAL_CONSTRAINT, weight, null, metadata);
                    }
                }
            }
        }

        // ── Assembly and validation ───────────────────────────────────────

        private void addDependency(Draft source, Draft target, DependencyType type, double weight,
                                   Integer minimumConnectionMinutes, Map<String, Object> metadata) {
            String key = key(source, target, type);
            if (dependencies.containsKey(key)) {
                return;
            }
            dependencies.put(key, new Dependency(syntheticUuid("dependency", key), source, target, type, weight,
                    minimumConnectionMinutes, metadata));
        }

        private SyntheticNetwork assemble() {
            List<SyntheticFlight> flights = new ArrayList<>(drafts.size());
            for (Draft d : drafts) {
                flights.add(new SyntheticFlight(d.flightId, d.scheduleId, d.flightNumber, d.airline, d.origin,
                        d.destination, d.serviceDate, d.departure, d.arrival, d.aircraftType,
                        d.tail.aircraftId, d.tail.registration));
            }
            Map<UUID, SyntheticFlight> flightsById = new HashMap<>();
            flights.forEach(f -> flightsById.put(f.flightInstanceId(), f));

            List<SyntheticDependency> result = new ArrayList<>(dependencies.size());
            List<String> violations = new ArrayList<>();
            for (Dependency d : dependencies.values()) {
                SyntheticFlight source = flightsById.get(d.source.flightId);
                SyntheticFlight target = flightsById.get(d.target.flightId);
                List<String> problems = DependencyRules.violations(source.toLeg(), target.toLeg(), d.type,
                        d.weight, d.minimumConnectionMinutes);
                violations.addAll(problems);
                result.add(new SyntheticDependency(d.id, d.source.flightId, d.target.flightId, d.type, d.weight,
                        d.minimumConnectionMinutes, Collections.unmodifiableMap(new LinkedHashMap<>(d.metadata))));
            }
            if (!violations.isEmpty()) {
                throw new IllegalStateException("Synthetic network violates dependency rules: "
                        + String.join("; ", violations.subList(0, Math.min(5, violations.size()))));
            }

            List<SyntheticAircraft> aircraft = new ArrayList<>(tails.size());
            for (Tail t : tails) {
                aircraft.add(new SyntheticAircraft(t.aircraftId, t.registration, t.aircraftType, t.airline,
                        t.initialStation, t.legs.size()));
            }
            return new SyntheticNetwork(startDate, days, seed, ReferenceAirports.ALL, aircraft, flights, result);
        }

        // ── Helpers ───────────────────────────────────────────────────────

        private Map<String, List<Draft>> departuresByStation() {
            Map<String, List<Draft>> byStation = new HashMap<>();
            for (Draft d : drafts) {   // drafts are sorted by departure
                byStation.computeIfAbsent(d.origin, k -> new ArrayList<>()).add(d);
            }
            return byStation;
        }

        private List<Draft> arrivalsAt(String station) {
            List<Draft> arrivals = new ArrayList<>();
            for (Draft d : drafts) {
                if (d.destination.equals(station)) {
                    arrivals.add(d);
                }
            }
            arrivals.sort(Comparator.comparing((Draft d) -> d.arrival).thenComparing(d -> d.flightNumber));
            return arrivals;
        }

        private Draft nextLegOf(Draft flight) {
            List<Draft> legs = flight.tail.legs;
            int index = legs.indexOf(flight);
            return index >= 0 && index + 1 < legs.size() ? legs.get(index + 1) : null;
        }

        private static String key(Draft source, Draft target, DependencyType type) {
            return type + ":" + source.flightId + ":" + target.flightId;
        }

        private static double distanceKm(String a, String b) {
            return ReferenceAirports.greatCircleKm(ReferenceAirports.require(a), ReferenceAirports.require(b));
        }

        private static LocalDate localDate(Instant instant, String iata) {
            ReferenceAirport airport = ReferenceAirports.require(iata);
            return instant.atZone(ZoneId.of(airport.timezone())).toLocalDate();
        }

        private static boolean isPair(Route route, String x, String y) {
            return (route.a().equals(x) && route.b().equals(y)) || (route.a().equals(y) && route.b().equals(x));
        }

        private static long minutesBetween(Instant from, Instant to) {
            return Duration.between(from, to).toMinutes();
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double round3(double value) {
            return Math.round(value * 1000.0) / 1000.0;
        }
    }

    private record PatternFlight(String flightNumber, String airline, String origin, String destination,
                                 int departureMinute, int blockMinutes, String aircraftType) {
    }

    private static final class Draft {
        final UUID flightId;
        final UUID scheduleId;
        final String flightNumber;
        final String airline;
        final String origin;
        final String destination;
        final LocalDate serviceDate;
        final Instant departure;
        final Instant arrival;
        final String aircraftType;
        Tail tail;

        Draft(UUID flightId, UUID scheduleId, String flightNumber, String airline, String origin,
              String destination, LocalDate serviceDate, Instant departure, Instant arrival, String aircraftType) {
            this.flightId = flightId;
            this.scheduleId = scheduleId;
            this.flightNumber = flightNumber;
            this.airline = airline;
            this.origin = origin;
            this.destination = destination;
            this.serviceDate = serviceDate;
            this.departure = departure;
            this.arrival = arrival;
            this.aircraftType = aircraftType;
        }
    }

    private static final class Tail {
        final UUID aircraftId;
        final String registration;
        final String aircraftType;
        final String airline;
        final String initialStation;
        final List<Draft> legs = new ArrayList<>();

        Tail(UUID aircraftId, String registration, String aircraftType, String airline, String station) {
            this.aircraftId = aircraftId;
            this.registration = registration;
            this.aircraftType = aircraftType;
            this.airline = airline;
            this.initialStation = station;
        }
    }

    /** One aircraft's daily line of flying (repeated on every service date). */
    private static final class Line {
        final Tail tail;
        final String base;
        final List<PatternFlight> legs = new ArrayList<>();
        String station;
        int readyMinute = FIRST_DEPARTURE_MINUTE;

        Line(Tail tail, String base) {
            this.tail = tail;
            this.base = base;
            this.station = base;
        }
    }

    private static final class Dependency {
        final UUID id;
        final Draft source;
        final Draft target;
        final DependencyType type;
        final double weight;
        final Integer minimumConnectionMinutes;
        final Map<String, Object> metadata;

        Dependency(UUID id, Draft source, Draft target, DependencyType type, double weight,
                   Integer minimumConnectionMinutes, Map<String, Object> metadata) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.type = type;
            this.weight = weight;
            this.minimumConnectionMinutes = minimumConnectionMinutes;
            this.metadata = metadata;
        }
    }
}
