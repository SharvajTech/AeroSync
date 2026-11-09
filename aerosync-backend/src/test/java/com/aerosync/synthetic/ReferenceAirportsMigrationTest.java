package com.aerosync.synthetic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps Flyway migration V2 and {@link ReferenceAirports} identical (ids, codes, coordinates). */
class ReferenceAirportsMigrationTest {

    private static String migration() throws IOException {
        try (InputStream in = ReferenceAirportsMigrationTest.class
                .getResourceAsStream("/db/migration/V2__reference_airports.sql")) {
            assertNotNull(in, "V2__reference_airports.sql must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void migrationContainsEveryReferenceAirportWithTheSameDeterministicId() throws IOException {
        String sql = migration();
        for (ReferenceAirports.ReferenceAirport airport : ReferenceAirports.ALL) {
            String row = String.format(Locale.ROOT, "('%s', '%s', '%s', '%s', '%s', %.4f, %.4f, 'OPERATIONAL')",
                    airport.airportId(), airport.iataCode(), airport.airportName().replace("'", "''"),
                    airport.city(), airport.timezone(), airport.latitude(), airport.longitude());
            assertTrue(sql.contains(row), "V2 is missing or differs for " + airport.iataCode() + ": " + row);
        }
        assertEquals(ReferenceAirports.ALL.size(), sql.split("'OPERATIONAL'\\)", -1).length - 1);
    }

    @Test
    void airportIdsAreDeterministicAndUnique() {
        Set<Object> ids = new HashSet<>();
        for (ReferenceAirports.ReferenceAirport airport : ReferenceAirports.ALL) {
            assertEquals(airport.airportId(), ReferenceAirports.airportUuid(airport.iataCode()));
            assertTrue(ids.add(airport.airportId()));
        }
        assertEquals(ReferenceAirports.GNN_TRAINING_AIRPORTS,
                ReferenceAirports.ALL.subList(0, 9).stream().map(ReferenceAirports.ReferenceAirport::iataCode).toList());
    }

    @Test
    void greatCircleDistancesMatchTheMlRouteTable() {
        // aerosync-ml/data_generator.py: BOM-DEL 1148 km, BOM-BLR 845 km, BOM-PNQ 120 km (within 3%).
        assertEquals(1148, ReferenceAirports.greatCircleKm(ReferenceAirports.require("BOM"), ReferenceAirports.require("DEL")), 35);
        assertEquals(845, ReferenceAirports.greatCircleKm(ReferenceAirports.require("BOM"), ReferenceAirports.require("BLR")), 25);
        assertEquals(120, ReferenceAirports.greatCircleKm(ReferenceAirports.require("BOM"), ReferenceAirports.require("PNQ")), 6);
    }
}
