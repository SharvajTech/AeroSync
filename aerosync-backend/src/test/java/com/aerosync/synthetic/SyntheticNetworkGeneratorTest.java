package com.aerosync.synthetic;

import com.aerosync.graph.DependencyRules;
import com.aerosync.model.DependencyType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SyntheticNetworkGeneratorTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Routes of aerosync-ml/data_generator.py ROUTES (the GNN / tabular training network). */
    private static final List<List<String>> GNN_ROUTES = List.of(
            List.of("BOM", "DEL"), List.of("BOM", "BLR"), List.of("DEL", "BLR"), List.of("BOM", "HYD"),
            List.of("DEL", "CCU"), List.of("BOM", "MAA"), List.of("DEL", "AMD"), List.of("BLR", "HYD"),
            List.of("DEL", "GOI"), List.of("BOM", "PNQ"));

    private final SyntheticNetwork network =
            SyntheticNetworkGenerator.generate(START, 7, SyntheticNetworkGenerator.DEFAULT_SEED);

    @Test
    void usesFifteenReferenceAirportsIncludingTheGnnAirports() {
        Set<String> codes = network.airports().stream().map(ReferenceAirports.ReferenceAirport::iataCode)
                .collect(Collectors.toSet());
        assertEquals(15, codes.size());
        assertTrue(codes.containsAll(ReferenceAirports.GNN_TRAINING_AIRPORTS));
        assertTrue(codes.containsAll(List.of("BOM", "BLR", "DEL", "HYD", "CCU", "MAA", "AMD", "GOI", "PNQ")));
        network.airports().forEach(a -> assertEquals("Asia/Kolkata", a.timezone()));
    }

    @Test
    void generatesBetween2500And3000FlightInstancesOverSevenDays() {
        int flights = network.flights().size();
        assertTrue(flights >= 2_500 && flights <= 3_000, "flights=" + flights);
        Map<LocalDate, Long> perDay = network.flights().stream()
                .collect(Collectors.groupingBy(SyntheticFlight::serviceDate, Collectors.counting()));
        assertEquals(7, perDay.size());
        assertEquals(1, new HashSet<>(perDay.values()).size(), "the same daily timetable repeats");
    }

    @Test
    void hasNoDuplicateFlightInstancesAndRepeatsFlightNumbersAcrossDates() {
        Set<String> legKeys = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (SyntheticFlight flight : network.flights()) {
            assertTrue(legKeys.add(flight.flightNumber() + "|" + flight.serviceDate() + "|" + flight.originIata()),
                    "duplicate leg " + flight);
            assertTrue(ids.add(flight.flightInstanceId()));
        }
        Map<String, Set<LocalDate>> datesPerNumber = network.flights().stream().collect(Collectors.groupingBy(
                SyntheticFlight::flightNumber, Collectors.mapping(SyntheticFlight::serviceDate, Collectors.toSet())));
        assertTrue(datesPerNumber.values().stream().allMatch(dates -> dates.size() == 7));
    }

    @Test
    void schedulesArePlausible() {
        for (SyntheticFlight flight : network.flights()) {
            assertTrue(ReferenceAirports.find(flight.originIata()).isPresent(), flight.toString());
            assertTrue(ReferenceAirports.find(flight.destinationIata()).isPresent(), flight.toString());
            assertNotEquals(flight.originIata(), flight.destinationIata());
            long block = Duration.between(flight.scheduledDeparture(), flight.scheduledArrival()).toMinutes();
            double km = ReferenceAirports.greatCircleKm(ReferenceAirports.require(flight.originIata()),
                    ReferenceAirports.require(flight.destinationIata()));
            assertEquals(SyntheticNetworkGenerator.blockMinutes(km, flight.aircraftType()), block, flight.toString());
            assertTrue(block >= 45 && block <= 240, flight + " block=" + block);
            LocalTime departure = flight.scheduledDeparture().atZone(IST).toLocalTime();
            assertFalse(departure.isBefore(LocalTime.of(5, 30)), flight.toString());
            assertFalse(departure.isAfter(LocalTime.of(23, 59)), flight.toString());
            assertEquals(flight.serviceDate(), flight.scheduledDeparture().atZone(IST).toLocalDate(),
                    "service date is the local departure date");
            assertTrue(Set.of("A320", "A321", "B737", "ATR72", "B777").contains(flight.aircraftType()));
            if (flight.aircraftType().equals("ATR72")) {
                assertTrue(km < 450, "ATR72 only on short routes: " + flight);
            }
        }
    }

    @Test
    void doesNotConnectEveryAirportToEveryOtherAirport() {
        Set<Set<String>> pairs = network.flights().stream()
                .map(f -> Set.of(f.originIata(), f.destinationIata())).collect(Collectors.toSet());
        assertTrue(pairs.size() < 15 * 14 / 2 / 2, "served city pairs: " + pairs.size());
        for (List<String> route : GNN_ROUTES) {
            assertTrue(pairs.contains(Set.copyOf(route)), "GNN route not served: " + route);
        }
    }

    @Test
    void everyAircraftFliesAContinuousRotationWithMinimumTurnarounds() {
        Map<UUID, List<SyntheticFlight>> byAircraft = network.flights().stream()
                .collect(Collectors.groupingBy(SyntheticFlight::aircraftId));
        assertEquals(network.aircraft().size(), byAircraft.size());
        for (SyntheticAircraft aircraft : network.aircraft()) {
            assertTrue(aircraft.registration().matches("VT-[A-Z][0-9A-Z][0-9]"), aircraft.registration());
            List<SyntheticFlight> legs = new ArrayList<>(byAircraft.get(aircraft.aircraftId()));
            legs.sort(Comparator.comparing(SyntheticFlight::scheduledDeparture));
            assertEquals(aircraft.initialAirportIata(), legs.get(0).originIata());
            for (int i = 0; i + 1 < legs.size(); i++) {
                SyntheticFlight current = legs.get(i);
                SyntheticFlight next = legs.get(i + 1);
                assertEquals(current.destinationIata(), next.originIata(), "station continuity " + current + " -> " + next);
                long ground = Duration.between(current.scheduledArrival(), next.scheduledDeparture()).toMinutes();
                assertTrue(ground >= SyntheticNetworkGenerator.minimumTurnaroundMinutes(aircraft.aircraftType()),
                        "turnaround " + ground + " min " + current + " -> " + next);
                assertEquals(aircraft.aircraftType(), next.aircraftType());
                assertEquals(aircraft.operatingAirline(), next.operatingAirline());
            }
            double legsPerDay = legs.size() / 7.0;
            assertTrue(legsPerDay >= 2 && legsPerDay <= 10, aircraft.registration() + " legs/day " + legsPerDay);
        }
    }

    @Test
    void everyDependencySatisfiesTheDependencyRules() {
        Map<UUID, SyntheticFlight> flights = new HashMap<>();
        network.flights().forEach(f -> flights.put(f.flightInstanceId(), f));
        Set<String> edgeKeys = new HashSet<>();
        for (SyntheticDependency dependency : network.dependencies()) {
            SyntheticFlight source = flights.get(dependency.sourceFlightId());
            SyntheticFlight target = flights.get(dependency.targetFlightId());
            assertNotNull(source);
            assertNotNull(target);
            List<String> violations = DependencyRules.violations(source.toLeg(), target.toLeg(), dependency.type(),
                    dependency.impactWeight(), dependency.minimumConnectionMinutes());
            if (!violations.isEmpty()) {
                fail(dependency + " -> " + violations);
            }
            assertTrue(edgeKeys.add(dependency.sourceFlightId() + ">" + dependency.targetFlightId() + ":" + dependency.type()));
            assertEquals(Boolean.TRUE, dependency.metadata().get("synthetic"));
        }
        Map<DependencyType, Integer> counts = network.dependencyCounts();
        for (DependencyType type : DependencyType.values()) {
            assertTrue(counts.get(type) > 0, "no " + type + " dependencies");
        }
        assertTrue(counts.get(DependencyType.AIRCRAFT_ROTATION) > counts.get(DependencyType.CREW_ROTATION));
    }

    @Test
    void containsTheSampleRouteBomAi101BlrAi202DelEveryDay() {
        Map<UUID, SyntheticFlight> flights = new HashMap<>();
        network.flights().forEach(f -> flights.put(f.flightInstanceId(), f));
        for (int day = 0; day < 7; day++) {
            LocalDate date = START.plusDays(day);
            SyntheticFlight ai101 = find("AI101", date);
            SyntheticFlight ai202 = find("AI202", date);
            assertEquals(List.of("BOM", "BLR"), List.of(ai101.originIata(), ai101.destinationIata()));
            assertEquals(List.of("BLR", "DEL"), List.of(ai202.originIata(), ai202.destinationIata()));
            assertNotEquals(ai101.aircraftId(), ai202.aircraftId());

            List<SyntheticDependency> fromAi101 = network.dependencies().stream()
                    .filter(d -> d.sourceFlightId().equals(ai101.flightInstanceId())).toList();
            assertTrue(fromAi101.stream().anyMatch(d -> d.type() == DependencyType.PASSENGER_CONNECTION
                    && d.targetFlightId().equals(ai202.flightInstanceId())), "AI101 -> AI202 connection on " + date);
            SyntheticDependency rotation = fromAi101.stream()
                    .filter(d -> d.type() == DependencyType.AIRCRAFT_ROTATION).findFirst().orElseThrow();
            assertEquals("AI505", flights.get(rotation.targetFlightId()).flightNumber());
            assertNotEquals(ai202.flightInstanceId(), rotation.targetFlightId());
        }
    }

    @Test
    void generatesOnlyPlannedDataAndIsDeterministic() {
        SyntheticNetwork again = SyntheticNetworkGenerator.generate(START, 7, SyntheticNetworkGenerator.DEFAULT_SEED);
        assertEquals(network, again);
        SyntheticNetwork otherSeed = SyntheticNetworkGenerator.generate(START, 7, 7L);
        assertNotEquals(network.flights(), otherSeed.flights());
        SyntheticNetwork oneDay = SyntheticNetworkGenerator.generate(START, 1, SyntheticNetworkGenerator.DEFAULT_SEED);
        assertEquals(network.flights().size() / 7, oneDay.flights().size());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticNetworkGenerator.generate(START, 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> SyntheticNetworkGenerator.generate(START, 32, 1L));
        assertThrows(NullPointerException.class, () -> SyntheticNetworkGenerator.generate(null, 7, 1L));
    }

    private SyntheticFlight find(String flightNumber, LocalDate date) {
        return network.flights().stream()
                .filter(f -> f.flightNumber().equals(flightNumber) && f.serviceDate().equals(date))
                .findFirst().orElseThrow(() -> new AssertionError(flightNumber + " missing on " + date));
    }
}
