package com.aerosync.ingestion;

import com.aerosync.ingestion.AirportDirectory.AirportRef;
import com.aerosync.ingestion.opensky.BoundingBox;
import com.aerosync.ingestion.opensky.OpenSkyFlight;
import com.aerosync.ingestion.opensky.OpenSkyStateVector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation and normalization of OpenSky records: what is derived (flight
 * number, nearest airport), what is rejected and why, and that nothing is
 * filled in for values OpenSky did not report.
 */
class OpenSkyNormalizerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final AirportRef BOM = new AirportRef(UUID.nameUUIDFromBytes("BOM".getBytes()), "BOM", "VABB", IST,
            19.0896, 72.8656);
    static final AirportRef DEL = new AirportRef(UUID.nameUUIDFromBytes("DEL".getBytes()), "DEL", "VIDP", IST,
            28.5562, 77.1000);
    static final AirportRef PNQ = new AirportRef(UUID.nameUUIDFromBytes("PNQ".getBytes()), "PNQ", "VAPO", IST,
            18.5821, 73.9197);
    static final AirportDirectory AIRPORTS = new AirportDirectory(List.of(BOM, DEL, PNQ));

    private static final Instant SNAPSHOT = Instant.parse("2026-11-10T10:00:00Z");
    private final OpenSkyNormalizer normalizer =
            new OpenSkyNormalizer(AIRPORTS, new CallsignMapper(), Duration.ofMinutes(5), 60);

    private static OpenSkyStateVector state(String icao24, String callsign, Double lat, Double lon,
                                            long lastContact, boolean onGround) {
        return new OpenSkyStateVector(icao24, callsign, "India", lastContact, lastContact, lon, lat, 1200.0,
                onGround, 80.0, 270.0, -3.0, 1250.0, null, 0);
    }

    // ── live states ─────────────────────────────────────────────────────────

    @Test
    void aStateNearAnAirportIsLinkedToItAndItsCallsignMapped() {
        Normalization<NormalizedAircraftState> result = normalizer.normalize(
                state("800c6d", "IGO1454", 19.20, 72.95, SNAPSHOT.getEpochSecond() - 5, false), SNAPSHOT, SNAPSHOT);

        assertTrue(result.isAccepted());
        NormalizedAircraftState state = result.value();
        assertEquals("6E1454", state.flightNumber());
        assertEquals("6E", state.operatingAirline());
        assertEquals("BOM", state.nearestAirportIata());
        assertEquals(BOM.airportId(), state.nearestAirportId());
        assertTrue(state.nearestAirportDistanceKm() > 10 && state.nearestAirportDistanceKm() < 20);
        assertEquals(19.20, state.latitude());
        assertEquals(1200.0, state.baroAltitudeM());
    }

    @Test
    void anAtcCallsignIsKeptButNoFlightNumberIsInvented() {
        NormalizedAircraftState state = normalizer.normalize(
                state("800c6d", "AIC5BH", 19.20, 72.95, SNAPSHOT.getEpochSecond(), false), SNAPSHOT, SNAPSHOT)
                .value();

        assertEquals("AIC5BH", state.callsign());
        assertNull(state.flightNumber());
        assertNull(state.operatingAirline());
    }

    @Test
    void anAircraftFarFromEveryAirportHasNoAirport() {
        NormalizedAircraftState state = normalizer.normalize(
                state("800c6d", "IGO1454", 23.5, 75.5, SNAPSHOT.getEpochSecond(), false), SNAPSHOT, SNAPSHOT)
                .value();

        assertNull(state.nearestAirportId());
        assertNull(state.nearestAirportIata());
        assertNull(state.nearestAirportDistanceKm());
    }

    @Test
    void statesWithoutAUsablePositionOrIdentityAreRejected() {
        long now = SNAPSHOT.getEpochSecond();
        assertEquals(RejectReason.NO_POSITION,
                normalizer.normalize(state("800c6d", "IGO1", null, 72.9, now, false), SNAPSHOT, SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_POSITION,
                normalizer.normalize(state("800c6d", "IGO1", 95.0, 72.9, now, false), SNAPSHOT, SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_AIRCRAFT_ID,
                normalizer.normalize(state("XYZ", "IGO1", 19.0, 72.9, now, false), SNAPSHOT, SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_AIRCRAFT_ID,
                normalizer.normalize(state(null, "IGO1", 19.0, 72.9, now, false), SNAPSHOT, SNAPSHOT).rejected());
    }

    @Test
    void oldAndFutureStatesAreNotCurrent() {
        long now = SNAPSHOT.getEpochSecond();
        assertEquals(RejectReason.STALE, normalizer.normalize(
                state("800c6d", "IGO1", 19.0, 72.9, now - 301, false), SNAPSHOT, SNAPSHOT).rejected());
        assertTrue(normalizer.normalize(
                state("800c6d", "IGO1", 19.0, 72.9, now - 299, false), SNAPSHOT, SNAPSHOT).isAccepted());
        assertEquals(RejectReason.FUTURE_TIMESTAMP, normalizer.normalize(
                state("800c6d", "IGO1", 19.0, 72.9, now + 600, false), SNAPSHOT, SNAPSHOT).rejected());
    }

    // ── flight legs ─────────────────────────────────────────────────────────

    private static OpenSkyFlight flight(String callsign, String from, String to, String departure,
                                        String arrival) {
        return new OpenSkyFlight("800c6d", Instant.parse(departure).getEpochSecond(), from,
                Instant.parse(arrival).getEpochSecond(), to, callsign);
    }

    @Test
    void aLegBetweenNetworkAirportsIsNormalizedWithObservedTimes() {
        Normalization<NormalizedFlightLeg> result = normalizer.normalize(
                flight("IGO0679", "VABB", "VIDP", "2026-11-09T20:10:00Z", "2026-11-09T22:15:00Z"), SNAPSHOT);

        assertTrue(result.isAccepted());
        NormalizedFlightLeg leg = result.value();
        assertEquals("6E679", leg.flightNumber());
        assertEquals("6E", leg.operatingAirline());
        assertEquals("IGO0679", leg.callsign());
        assertEquals("BOM", leg.origin().iata());
        assertEquals("DEL", leg.destination().iata());
        assertEquals(Instant.parse("2026-11-09T20:10:00Z"), leg.departure());
        assertEquals(Instant.parse("2026-11-09T22:15:00Z"), leg.arrival());
        // 20:10 UTC is 01:40 the next day in Mumbai: the leg belongs to the local date.
        assertEquals(LocalDate.of(2026, 11, 10), leg.serviceDate());
        assertEquals("800c6d:" + Instant.parse("2026-11-09T20:10:00Z").getEpochSecond(), leg.sourceRecordId());
    }

    @Test
    void legsThatCannotBeTrustedAreRejectedWithTheReason() {
        assertEquals(RejectReason.MISSING_AIRPORT, normalizer.normalize(
                flight("IGO679", "VABB", null, "2026-11-09T08:00:00Z", "2026-11-09T10:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.AIRPORT_NOT_IN_NETWORK, normalizer.normalize(
                flight("IGO679", "VABB", "OMDB", "2026-11-09T08:00:00Z", "2026-11-09T11:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.SAME_AIRPORT, normalizer.normalize(
                flight("IGO679", "VABB", "VABB", "2026-11-09T08:00:00Z", "2026-11-09T09:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.UNMAPPED_CALLSIGN, normalizer.normalize(
                flight("AIC5BH", "VABB", "VIDP", "2026-11-09T08:00:00Z", "2026-11-09T10:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.UNMAPPED_CALLSIGN, normalizer.normalize(
                flight(null, "VABB", "VIDP", "2026-11-09T08:00:00Z", "2026-11-09T10:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_TIMES, normalizer.normalize(
                flight("IGO679", "VABB", "VIDP", "2026-11-09T10:00:00Z", "2026-11-09T10:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_TIMES, normalizer.normalize(
                flight("IGO679", "VABB", "VIDP", "2026-11-08T08:00:00Z", "2026-11-09T08:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.FUTURE_TIMESTAMP, normalizer.normalize(
                flight("IGO679", "VABB", "VIDP", "2026-11-10T09:00:00Z", "2026-11-10T11:00:00Z"), SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_TIMES, normalizer.normalize(
                new OpenSkyFlight("800c6d", null, "VABB", 1794275400L, "VIDP", "IGO679"), SNAPSHOT).rejected());
        assertEquals(RejectReason.INVALID_AIRCRAFT_ID, normalizer.normalize(
                new OpenSkyFlight("nothex", 1794268800L, "VABB", 1794275400L, "VIDP", "IGO679"), SNAPSHOT).rejected());
    }

    // ── supporting pieces ───────────────────────────────────────────────────

    @Test
    void callsignsMapOnlyWhenTheyAreNumericAndTheAirlineIsKnown() {
        CallsignMapper mapper = new CallsignMapper();
        assertEquals("AI101", mapper.toFlight("AIC101").orElseThrow().flightNumber());
        assertEquals("6E1454", mapper.toFlight(" igo1454 ").orElseThrow().flightNumber());
        assertEquals("SG8001", mapper.toFlight("SEJ8001").orElseThrow().flightNumber());
        assertEquals("IX1", mapper.toFlight("AXB001").orElseThrow().flightNumber());
        assertTrue(mapper.toFlight("IGO89H").isEmpty());       // ATC callsign, not a flight number
        assertTrue(mapper.toFlight("AIC5BH").isEmpty());
        assertTrue(mapper.toFlight("UAE512").isEmpty());       // airline not in the table
        assertTrue(mapper.toFlight("VTAAA").isEmpty());        // registration used as callsign
        assertTrue(mapper.toFlight("IGO12345").isEmpty());
        assertTrue(mapper.toFlight("IGO0").isEmpty());
        assertTrue(mapper.toFlight(null).isEmpty());

        CallsignMapper extended = CallsignMapper.withExtra("uae=EK, FDB=FZ");
        assertEquals("EK512", extended.toFlight("UAE512").orElseThrow().flightNumber());
        assertEquals("AI101", extended.toFlight("AIC101").orElseThrow().flightNumber());
    }

    @Test
    void theDirectoryFindsTheNearestAirportAndBuildsTheQueryBox() {
        assertEquals("BOM", AIRPORTS.nearest(19.2, 72.95, 60).orElseThrow().airport().iata());
        assertEquals("PNQ", AIRPORTS.nearest(18.6, 73.8, 60).orElseThrow().airport().iata());
        assertTrue(AIRPORTS.nearest(23.5, 75.5, 60).isEmpty());
        assertEquals("DEL", AIRPORTS.byIcao("vidp").orElseThrow().iata());
        assertTrue(AIRPORTS.byIcao("OMDB").isEmpty());
        // Mumbai to Delhi is about 1,140 km.
        double km = AirportDirectory.haversineKm(BOM.latitude(), BOM.longitude(), DEL.latitude(), DEL.longitude());
        assertTrue(km > 1100 && km < 1180, "was " + km);

        BoundingBox box = AIRPORTS.boundingBox(0.75);
        assertEquals(18.5821 - 0.75, box.minLatitude(), 1e-9);
        assertEquals(28.5562 + 0.75, box.maxLatitude(), 1e-9);
        assertEquals(72.8656 - 0.75, box.minLongitude(), 1e-9);
        assertEquals(77.1000 + 0.75, box.maxLongitude(), 1e-9);
    }

    @Test
    void thePollIntervalIsRaisedToWhatTheCreditBudgetCanPayFor() {
        IngestionSettings settings = new IngestionSettings(true, Duration.ofSeconds(300), "", 0.75,
                Duration.ofMinutes(5), 60, Duration.ofHours(6), true, Duration.ofMinutes(60), 2, 20, 0,
                Duration.ofSeconds(60), Duration.ofMinutes(30));

        // OpenSky prices a states request by the area of the box.
        assertEquals(1, new BoundingBox(18, 72, 20, 74).creditCost());
        assertEquals(2, new BoundingBox(10, 70, 20, 80).creditCost());
        assertEquals(3, new BoundingBox(10, 70, 30, 90).creditCost());
        assertEquals(4, new BoundingBox(8, 68, 30, 98).creditCost());

        // Anonymous: 400 credits a day. A 4-credit box allows 90 polls a day at 90 % of the budget.
        assertEquals(Duration.ofSeconds(960), settings.effectiveStatesInterval(4, false));
        // A standard account (4,000 credits) can afford the wanted 300 s.
        assertEquals(Duration.ofSeconds(300), settings.effectiveStatesInterval(4, true));
        // A small box is cheap enough even anonymously.
        assertEquals(Duration.ofSeconds(300), settings.effectiveStatesInterval(1, false));
        assertFalse(settings.effectiveStatesInterval(4, false).compareTo(Duration.ofSeconds(300)) < 0);
    }
}
