package com.aerosync.datasource;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import com.aerosync.model.Flight;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.security.AirportScope;
import com.aerosync.service.DisruptionService;
import com.aerosync.testsupport.InMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data source mode, and how it combines with the airport scope when
 * disruptions are listed: synthetic records never appear in LIVE mode, and a
 * user never sees another airport's disruptions in any mode.
 */
class DataSourceModeTest {

    private final AtomicLong ids = new AtomicLong();
    private InMemoryRepository<Flight, Long> flights;
    private InMemoryRepository<Disruption, Long> disruptions;

    @BeforeEach
    void setUp() {
        flights = InMemoryRepository.of(Flight::getId, f -> f.setId(ids.incrementAndGet()));
        disruptions = InMemoryRepository.of(Disruption::getId, d -> d.setId(ids.incrementAndGet()));
        disruptions.query("findByStatus", (rows, args) -> rows.stream()
                .filter(d -> d.getStatus() == args[0]).toList());
    }

    private DisruptionService service(DataSourceMode mode) {
        return new DisruptionService(disruptions.as(DisruptionRepository.class), flights.as(FlightRepository.class),
                new DataSourcePolicy(mode));
    }

    private void flight(String id, String origin, String destination, DataOrigin origin2) {
        flights.as(FlightRepository.class).save(Flight.builder().flightId(id).origin(origin)
                .destination(destination).status(Flight.FlightStatus.SCHEDULED).dataOrigin(origin2).build());
    }

    private Disruption disruption(String flightId, String origin, String destination, DataOrigin dataOrigin,
                                  UUID flightInstanceId) {
        return disruptions.as(DisruptionRepository.class).save(Disruption.builder().flightId(flightId)
                .status(DisruptionStatus.DETECTED).originAirport(origin).destinationAirport(destination)
                .dataOrigin(dataOrigin).flightInstanceId(flightInstanceId).build());
    }

    private static List<String> flightIds(List<Disruption> disruptions) {
        return disruptions.stream().map(Disruption::getFlightId).sorted().toList();
    }

    @Test
    void eachModeServesItsOwnOriginsAndManualRecordsAlways() {
        assertTrue(DataSourceMode.SYNTHETIC.serves(DataOrigin.SYNTHETIC));
        assertFalse(DataSourceMode.SYNTHETIC.serves(DataOrigin.IMPORTED));
        assertFalse(DataSourceMode.LIVE.serves(DataOrigin.SYNTHETIC));
        assertTrue(DataSourceMode.LIVE.serves(DataOrigin.IMPORTED));
        assertTrue(DataSourceMode.HYBRID.serves(DataOrigin.SYNTHETIC));
        assertTrue(DataSourceMode.HYBRID.serves(DataOrigin.IMPORTED));
        for (DataSourceMode mode : DataSourceMode.values()) {
            assertTrue(mode.serves(DataOrigin.MANUAL));
        }
        // A record without an origin is treated as synthetic, never as live.
        assertFalse(DataSourceMode.LIVE.serves(null));
        assertTrue(DataSourceMode.SYNTHETIC.serves(null));

        assertFalse(DataSourceMode.SYNTHETIC.ingestsExternalData());
        assertTrue(DataSourceMode.LIVE.ingestsExternalData());
        assertFalse(DataSourceMode.LIVE.generatesSyntheticData());
        assertTrue(DataSourceMode.HYBRID.generatesSyntheticData());
        assertEquals(List.of(DataOrigin.IMPORTED, DataOrigin.MANUAL),
                new DataSourcePolicy(DataSourceMode.LIVE).servedOrigins());
    }

    @Test
    void theModeIsParsedFromConfigurationAndDefaultsToSynthetic() {
        assertEquals(DataSourceMode.SYNTHETIC, DataSourceMode.parse(null));
        assertEquals(DataSourceMode.SYNTHETIC, DataSourceMode.parse("  "));
        assertEquals(DataSourceMode.HYBRID, DataSourceMode.parse(" hybrid "));
        assertEquals(DataSourceMode.LIVE, new DataSourcePolicy("Live").mode());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> DataSourceMode.parse("real"));
        assertTrue(ex.getMessage().contains("SYNTHETIC, LIVE or HYBRID"));

        assertEquals(DataSources.SYNTHETIC_GENERATOR, DataSources.defaultFor(DataOrigin.SYNTHETIC));
        assertEquals(DataSources.SYNTHETIC_GENERATOR, DataSources.defaultFor(null));
        assertEquals(DataSources.MANUAL_ENTRY, DataSources.defaultFor(DataOrigin.MANUAL));
    }

    @Test
    void disruptionsAreLimitedToTheUsersAirportsIncludingAdvisoryOnes() {
        disruption("AI-101", "BOM", "DEL", DataOrigin.SYNTHETIC, null);
        disruption("AI-201", "DEL", "BLR", DataOrigin.SYNTHETIC, null);
        disruption("6E700", "HYD", "BLR", DataOrigin.SYNTHETIC, UUID.randomUUID());     // advisory
        disruption("6E800", "BOM", "HYD", DataOrigin.SYNTHETIC, UUID.randomUUID());     // advisory
        DisruptionService service = service(DataSourceMode.SYNTHETIC);

        assertEquals(List.of("6E800", "AI-101"), flightIds(service.getDisruptions(AirportScope.of("BOM"))));
        // Delhi sees its own departure and the Mumbai flight arriving there, nothing of Hyderabad.
        assertEquals(List.of("AI-101", "AI-201"), flightIds(service.getDisruptions(AirportScope.of("DEL"))));
        assertEquals(List.of("6E700", "6E800"), flightIds(service.getActiveDisruptions(AirportScope.of("HYD"))));
        assertTrue(service.getDisruptions(AirportScope.of("CCU")).isEmpty());
        assertTrue(service.getDisruptions(AirportScope.of()).isEmpty());
        assertEquals(4, service.getDisruptions(AirportScope.unrestricted()).size());
    }

    @Test
    void syntheticDisruptionsDisappearInLiveModeAndImportedOnesInSyntheticMode() {
        disruption("AI-101", "BOM", "DEL", DataOrigin.SYNTHETIC, null);
        disruption("6E679", "BOM", "DEL", DataOrigin.IMPORTED, UUID.randomUUID());
        disruption("AI-900", "BOM", "DEL", DataOrigin.MANUAL, null);
        AirportScope mumbai = AirportScope.of("BOM");

        assertEquals(List.of("AI-101", "AI-900"), flightIds(service(DataSourceMode.SYNTHETIC).getDisruptions(mumbai)));
        assertEquals(List.of("6E679", "AI-900"), flightIds(service(DataSourceMode.LIVE).getDisruptions(mumbai)));
        assertEquals(3, service(DataSourceMode.HYBRID).getDisruptions(mumbai).size());
        assertEquals(List.of("6E679", "AI-900"),
                flightIds(service(DataSourceMode.LIVE).getAllDisruptions()));
    }

    @Test
    void olderDisruptionsWithoutStoredAirportsFallBackToTheirFlight() {
        flight("AI-101", "BOM", "DEL", null);                // a row from before Stage 4: synthetic
        flight("AI-900", "BOM", "DEL", DataOrigin.MANUAL);
        disruption("AI-101", null, null, null, null);
        disruption("AI-900", null, null, null, null);
        disruption("GONE-1", null, null, null, null);        // its flight no longer exists

        assertEquals(List.of("AI-101", "AI-900"),
                flightIds(service(DataSourceMode.SYNTHETIC).getDisruptions(AirportScope.of("DEL"))));
        assertTrue(service(DataSourceMode.SYNTHETIC).getDisruptions(AirportScope.of("HYD")).isEmpty());
        assertEquals(List.of("AI-900"),
                flightIds(service(DataSourceMode.LIVE).getDisruptions(AirportScope.of("BOM"))));
        // System callers still see a disruption whose flight is gone (unchanged behaviour).
        assertEquals(3, service(DataSourceMode.SYNTHETIC).getAllDisruptions().size());
    }

    @Test
    void aReportedDisruptionRecordsWhoRaisedItAndWhichAirportsItBelongsTo() {
        flights.query("findByFlightId", (rows, args) -> rows.stream()
                .filter(f -> f.getFlightId().equals(args[0])).findFirst());
        flight("AI-101", "BOM", "DEL", DataOrigin.SYNTHETIC);
        DisruptionService service = service(DataSourceMode.SYNTHETIC);

        Disruption reported = service.triggerDisruption("AI-101", Disruption.DisruptionType.AIRCRAFT_FAULT, 120,
                AirportScope.of("BOM"));

        assertEquals(DisruptionService.SOURCE_MANUAL, reported.getDetectionSource());
        assertEquals(Disruption.DisruptionSeverity.HIGH, reported.getSeverity());
        assertEquals(1.0, reported.getConfidence());
        assertEquals("BOM", reported.getOriginAirport());
        assertEquals("DEL", reported.getDestinationAirport());
        assertEquals(DataOrigin.SYNTHETIC, reported.getDataOrigin());
        assertTrue(reported.isActionable());

        service.recordProposed(reported.getId());
        service.recordRejected(reported.getId(), "bom.ops");
        service.recordRejected(reported.getId(), "bom.ops");
        service.recordApproved(reported.getId(), "bom.lead");
        assertEquals(2, reported.getRejectionCount());
        assertEquals("bom.ops", reported.getRejectedBy());
        assertEquals("bom.lead", reported.getApprovedBy());

        // In LIVE mode the synthetic flight does not exist for the API.
        assertThrows(RuntimeException.class, () -> service(DataSourceMode.LIVE).triggerDisruption(
                "AI-101", Disruption.DisruptionType.AIRCRAFT_FAULT, 60, AirportScope.of("BOM")));
    }
}
