package com.aerosync.graph;

import com.aerosync.model.AirportStatus;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.DependencyType;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.synthetic.ReferenceAirports;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small hand-built network used by the pure graph tests:
 *
 * <pre>
 *   BOM -AI101-> BLR -AI202-> DEL          (passenger connection AI101 -> AI202)
 *   AI101 and AI505 are flown by tail T1     (aircraft rotation AI101 -> AI505 -> AI506)
 *   AI201 (DEL-BLR) and AI202 by tail T2     (aircraft rotation AI201 -> AI202)
 * </pre>
 */
public final class SampleNetwork {

    public static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final UUID TAIL_1 = id("tail-1");
    public static final UUID TAIL_2 = id("tail-2");

    public final FlightRecord ai101 = flight("AI101", "BOM", "BLR", DAY, 7, 0, 100, TAIL_1, "VT-T01");
    public final FlightRecord ai505 = flight("AI505", "BLR", "HYD", DAY, 9, 30, 70, TAIL_1, "VT-T01");
    public final FlightRecord ai506 = flight("AI506", "HYD", "BLR", DAY, 11, 40, 70, TAIL_1, "VT-T01");
    public final FlightRecord ai201 = flight("AI201", "DEL", "BLR", DAY, 6, 15, 170, TAIL_2, "VT-T02");
    public final FlightRecord ai202 = flight("AI202", "BLR", "DEL", DAY, 10, 30, 170, TAIL_2, "VT-T02");

    public final DependencyRecord ai101ToAi202 =
            dependency(ai101, ai202, DependencyType.PASSENGER_CONNECTION, 0.45, 45, Map.of("hub", "BLR"));
    public final DependencyRecord ai101ToAi505 =
            dependency(ai101, ai505, DependencyType.AIRCRAFT_ROTATION, 0.93, 30, Map.of("station", "BLR"));
    public final DependencyRecord ai505ToAi506 =
            dependency(ai505, ai506, DependencyType.AIRCRAFT_ROTATION, 0.9, 30, Map.of("station", "HYD"));
    public final DependencyRecord ai201ToAi202 =
            dependency(ai201, ai202, DependencyType.AIRCRAFT_ROTATION, 0.8, 30, Map.of("station", "BLR"));

    public List<FlightRecord> flights() {
        return List.of(ai101, ai505, ai506, ai201, ai202);
    }

    public List<DependencyRecord> dependencies() {
        return List.of(ai101ToAi202, ai101ToAi505, ai505ToAi506, ai201ToAi202);
    }

    /** Builder with all reference airports registered, the sample flights and their dependencies. */
    public GraphBuilder builder() {
        GraphBuilder builder = new GraphBuilder();
        ReferenceAirports.ALL.forEach(a -> builder.registerAirport(airport(a.iataCode())));
        builder.addFlights(flights());
        builder.addDependencies(dependencies());
        return builder;
    }

    public static AirportRecord airport(String iata) {
        ReferenceAirports.ReferenceAirport reference = ReferenceAirports.require(iata);
        return new AirportRecord(reference.airportId(), reference.iataCode(), reference.airportName(),
                reference.city(), reference.timezone(), reference.latitude(), reference.longitude(),
                AirportStatus.OPERATIONAL);
    }

    public static Instant ist(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), IST).toInstant();
    }

    public static FlightRecord flight(String number, String origin, String destination, LocalDate date,
                                      int hour, int minute, int blockMinutes, UUID aircraftId, String registration) {
        Instant departure = ist(date, hour, minute);
        return new FlightRecord(id("flight:" + number + ":" + date + ":" + origin), id("schedule:" + number + date),
                number, date, origin, destination, departure, departure.plusSeconds(blockMinutes * 60L),
                null, null, null, null, 0, FlightInstanceStatus.SCHEDULED, number.substring(0, 2), "A320",
                aircraftId, registration, "A320", null, DataOrigin.MANUAL);
    }

    public static DependencyRecord dependency(FlightRecord source, FlightRecord target, DependencyType type,
                                              double weight, Integer minimumConnectionMinutes,
                                              Map<String, Object> metadata) {
        return new DependencyRecord(id("dependency:" + type + source.flightId() + target.flightId()),
                source.flightId(), target.flightId(), type, weight, minimumConnectionMinutes, true, metadata,
                source.scheduledDeparture(), target.scheduledDeparture(), DataOrigin.MANUAL);
    }

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
