package com.aerosync.ingestion;

import com.aerosync.ingestion.opensky.OpenSkyFlight;
import com.aerosync.ingestion.opensky.OpenSkyResponseParser;
import com.aerosync.ingestion.opensky.OpenSkyStateVector;
import com.aerosync.ingestion.opensky.OpenSkyStates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the documented OpenSky JSON, including the gaps real responses have. */
class OpenSkyResponseParserTest {

    @Test
    void stateVectorFieldsAreReadByTheirDocumentedIndex() {
        OpenSkyStates states = OpenSkyResponseParser.parseStates("""
                {"time":1794332252,"states":[
                  ["800c6d","IGO1454 ","India",1794332250,1794332251,72.9123,19.0891,129.54,false,71.5,269.3,-3.25,[1,2],121.92,"7402",false,0]
                ]}""");

        assertEquals(1794332252L, states.time());
        assertEquals(0, states.malformed());
        OpenSkyStateVector state = states.states().get(0);
        assertEquals("800c6d", state.icao24());
        assertEquals("IGO1454", state.callsign());           // padding removed
        assertEquals("India", state.originCountry());
        assertEquals(1794332250L, state.timePosition());
        assertEquals(1794332251L, state.lastContact());
        assertEquals(72.9123, state.longitude());
        assertEquals(19.0891, state.latitude());
        assertEquals(129.54, state.baroAltitude());
        assertFalse(state.onGround());
        assertEquals(71.5, state.velocity());
        assertEquals(269.3, state.trueTrack());
        assertEquals(-3.25, state.verticalRate());
        assertEquals(121.92, state.geoAltitude());
        assertEquals("7402", state.squawk());
        assertEquals(0, state.positionSource());
    }

    @Test
    void missingValuesStayNullInsteadOfBecomingZero() {
        OpenSkyStates states = OpenSkyResponseParser.parseStates("""
                {"time":1794332252,"states":[
                  ["800abc","        ","India",null,1794332251,null,null,null,true,null,null,null,null,null,null,false,0]
                ]}""");

        OpenSkyStateVector state = states.states().get(0);
        assertNull(state.callsign());
        assertNull(state.timePosition());
        assertNull(state.longitude());
        assertNull(state.latitude());
        assertNull(state.baroAltitude());
        assertNull(state.velocity());
        assertNull(state.squawk());
        assertTrue(state.onGround());
    }

    @Test
    void malformedEntriesAreDroppedAndCountedNotFatal() {
        OpenSkyStates states = OpenSkyResponseParser.parseStates("""
                {"time":1794332252,"states":[
                  ["800c6d","IGO1454 ","India",1794332250,1794332251,72.9,19.0,129.5,false,71.5,269.3,-3.2,null,121.9,null,false,0],
                  ["tooshort", 1, 2],
                  "not-an-array",
                  [null,"AIC101  ","India",1794332250,1794332251,72.9,19.0,129.5,false,71.5,269.3,-3.2,null,121.9,null,false,0],
                  ["800aaa","AIC101  ","India",1794332250,null,72.9,19.0,129.5,false,71.5,269.3,-3.2,null,121.9,null,false,0]
                ]}""");

        assertEquals(1, states.states().size());
        assertEquals(4, states.malformed());
    }

    @Test
    void nullStatesMeansNoAircraftInTheBox() {
        OpenSkyStates states = OpenSkyResponseParser.parseStates("{\"time\":1794332252,\"states\":null}");

        assertTrue(states.states().isEmpty());
        assertEquals(0, states.malformed());
    }

    @Test
    void aBodyThatIsNotTheStatesObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OpenSkyResponseParser.parseStates("[]"));
        assertThrows(IllegalArgumentException.class, () -> OpenSkyResponseParser.parseStates("not json"));
        assertThrows(IllegalArgumentException.class, () -> OpenSkyResponseParser.parseStates(""));
        assertThrows(IllegalArgumentException.class, () -> OpenSkyResponseParser.parseStates("{\"states\":[]}"));
    }

    @Test
    void flightsKeepUnknownAirportsAsNull() {
        List<OpenSkyFlight> flights = OpenSkyResponseParser.parseFlights("""
                [{"icao24":"800C6D","firstSeen":1794268800,"estDepartureAirport":"vabb","lastSeen":1794275400,
                  "estArrivalAirport":null,"callsign":"AIC5BH  ","departureAirportCandidatesCount":1},
                 {"icao24":"800c6e","firstSeen":null,"estDepartureAirport":"VIDP","lastSeen":1794275400,
                  "estArrivalAirport":"VABB","callsign":null},
                 42]""");

        assertEquals(2, flights.size());
        assertEquals(new OpenSkyFlight("800c6d", 1794268800L, "VABB", 1794275400L, null, "AIC5BH"), flights.get(0));
        assertNull(flights.get(1).firstSeen());
        assertNull(flights.get(1).callsign());
    }

    @Test
    void aFlightsBodyThatIsNotAnArrayIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OpenSkyResponseParser.parseFlights("{\"error\":1}"));
    }
}
