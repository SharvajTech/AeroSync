package com.aerosync.graph;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkIdentifiersTest {

    @Test
    void buildsStableNodeIds() {
        UUID id = UUID.fromString("0b6f3c1e-8d5f-4a5e-9a51-2d3c4b5a6f70");
        assertEquals("airport:BOM", NetworkIdentifiers.airportNodeId("bom"));
        assertEquals("flight:0b6f3c1e-8d5f-4a5e-9a51-2d3c4b5a6f70", NetworkIdentifiers.flightNodeId(id));
        assertEquals("departs_from:" + id, NetworkIdentifiers.departsFromEdgeId(id));
        assertEquals("arrives_at:" + id, NetworkIdentifiers.arrivesAtEdgeId(id));
        assertEquals("dependency:" + id, NetworkIdentifiers.dependencyEdgeId(id));
    }

    @Test
    void normalizesAndValidatesIataCodes() {
        assertEquals("BLR", NetworkIdentifiers.normalizeIata(" blr "));
        assertThrows(IllegalArgumentException.class, () -> NetworkIdentifiers.normalizeIata("B1R"));
        assertThrows(IllegalArgumentException.class, () -> NetworkIdentifiers.normalizeIata("BLRX"));
        assertThrows(IllegalArgumentException.class, () -> NetworkIdentifiers.normalizeIata(null));
    }

    @Test
    void parsesFlightReferencesButNeverFlightNumbers() {
        UUID id = UUID.randomUUID();
        assertEquals(id, NetworkIdentifiers.parseFlightReference(id.toString()));
        assertEquals(id, NetworkIdentifiers.parseFlightReference("flight:" + id));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> NetworkIdentifiers.parseFlightReference("AI101"));
        assertTrue(ex.getMessage().contains("not unique across dates"));
        assertThrows(IllegalArgumentException.class, () -> NetworkIdentifiers.parseFlightReference("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> NetworkIdentifiers.parseFlightReference(" "));
    }

    @Test
    void validatesFlightNumbers() {
        assertTrue(NetworkIdentifiers.isValidFlightNumber("AI101"));
        assertTrue(NetworkIdentifiers.isValidFlightNumber("6E2043"));
        assertTrue(NetworkIdentifiers.isValidFlightNumber("SG8001A"));
        assertFalse(NetworkIdentifiers.isValidFlightNumber("12345"));
        assertFalse(NetworkIdentifiers.isValidFlightNumber("AI12345"));
        assertFalse(NetworkIdentifiers.isValidFlightNumber("AI-101"));
        assertFalse(NetworkIdentifiers.isValidFlightNumber(null));
    }
}
