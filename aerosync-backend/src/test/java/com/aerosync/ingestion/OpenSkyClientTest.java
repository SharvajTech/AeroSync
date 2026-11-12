package com.aerosync.ingestion;

import com.aerosync.ingestion.StubHttpServer.Reply;
import com.aerosync.ingestion.opensky.BoundingBox;
import com.aerosync.ingestion.opensky.OpenSkyClient;
import com.aerosync.ingestion.opensky.OpenSkyException;
import com.aerosync.ingestion.opensky.OpenSkyException.Kind;
import com.aerosync.ingestion.opensky.OpenSkyFlight;
import com.aerosync.ingestion.opensky.OpenSkySettings;
import com.aerosync.ingestion.opensky.OpenSkyStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenSky client against a local stand-in for the OpenSky REST API and
 * its OAuth2 token endpoint: authentication, token renewal, rate limits,
 * retries with backoff, timeouts and error mapping. No request leaves the
 * machine.
 */
class OpenSkyClientTest {

    private static final BoundingBox BOX = new BoundingBox(18.0, 72.0, 20.0, 74.0);
    private static final String ONE_STATE = """
            {"time":1794332252,"states":[
              ["800c6d","IGO1454 ","India",1794332250,1794332251,72.9123,19.0891,129.54,false,71.5,269.3,-3.25,null,121.92,null,false,0]
            ]}""";
    private static final String TOKEN = "{\"access_token\":\"tok-1\",\"expires_in\":1800}";
    private static final String SECRET = "s3cr3t-value-never-logged";

    private StubHttpServer server;
    private MutableClock clock;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() {
        server = new StubHttpServer();
        clock = new MutableClock(Instant.parse("2026-11-10T10:00:00Z"));
        sleeps.clear();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OpenSkyClient client(boolean authenticated, int maxAttempts, Duration requestTimeout) {
        OpenSkySettings settings = new OpenSkySettings(server.url() + "/api", server.url() + "/token",
                authenticated ? "client-1" : "", authenticated ? SECRET : "",
                Duration.ofSeconds(2), requestTimeout, maxAttempts, Duration.ofMillis(100), Duration.ofMillis(250));
        return new OpenSkyClient(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock, sleeps::add);
    }

    @Test
    void anonymousRequestSendsNoCredentialsAndParsesTheStates() {
        server.on("/api/states/all", Reply.of(200, ONE_STATE).header("X-Rate-Limit-Remaining", "396"));

        OpenSkyClient client = client(false, 3, Duration.ofSeconds(2));
        OpenSkyStates states = client.fetchStates(BOX);

        assertEquals(1794332252L, states.time());
        assertEquals(1, states.states().size());
        assertEquals("800c6d", states.states().get(0).icao24());
        assertEquals("IGO1454", states.states().get(0).callsign());
        assertNull(server.received("/api/states/all").get(0).authorization());
        assertEquals("lamin=18.0000&lomin=72.0000&lamax=20.0000&lomax=74.0000",
                server.received("/api/states/all").get(0).query());
        assertEquals(0, server.received("/token").size());
        assertEquals(396, client.rateLimitRemaining().orElseThrow());
        assertFalse(client.authenticated());
        assertEquals(1, client.callsLastOperation());
    }

    @Test
    void clientCredentialsTokenIsRequestedOnceAndReused() {
        server.on("/token", Reply.of(200, TOKEN));
        server.on("/api/states/all", Reply.of(200, ONE_STATE));

        OpenSkyClient client = client(true, 3, Duration.ofSeconds(2));
        client.fetchStates(BOX);
        client.fetchStates(BOX);

        assertEquals(1, server.received("/token").size());
        StubHttpServer.Received tokenRequest = server.received("/token").get(0);
        assertEquals("POST", tokenRequest.method());
        assertTrue(tokenRequest.body().contains("grant_type=client_credentials"));
        assertTrue(tokenRequest.body().contains("client_id=client-1"));
        assertEquals(2, server.received("/api/states/all").size());
        server.received("/api/states/all").forEach(request ->
                assertEquals("Bearer tok-1", request.authorization()));
    }

    @Test
    void tokenIsRenewedShortlyBeforeItExpires() {
        server.on("/token", Reply.of(200, TOKEN),
                Reply.of(200, "{\"access_token\":\"tok-2\",\"expires_in\":1800}"));
        server.on("/api/states/all", Reply.of(200, ONE_STATE));

        OpenSkyClient client = client(true, 3, Duration.ofSeconds(2));
        client.fetchStates(BOX);
        clock.advance(Duration.ofMinutes(28));      // 2 minutes left: still valid
        client.fetchStates(BOX);
        assertEquals(1, server.received("/token").size());

        clock.advance(Duration.ofSeconds(90));      // 30 seconds left: inside the renewal margin
        client.fetchStates(BOX);
        assertEquals(2, server.received("/token").size());
        assertEquals("Bearer tok-2", server.received("/api/states/all").get(2).authorization());
    }

    @Test
    void anUnauthorizedAnswerRenewsTheTokenOnceAndRetries() {
        server.on("/token", Reply.of(200, TOKEN),
                Reply.of(200, "{\"access_token\":\"tok-2\",\"expires_in\":1800}"));
        server.on("/api/states/all", Reply.of(401, ""), Reply.of(200, ONE_STATE));

        OpenSkyStates states = client(true, 3, Duration.ofSeconds(2)).fetchStates(BOX);

        assertEquals(1, states.states().size());
        assertEquals(2, server.received("/token").size());
        assertEquals("Bearer tok-2", server.received("/api/states/all").get(1).authorization());
        assertTrue(sleeps.isEmpty(), "a token renewal is not a backoff");
    }

    @Test
    void aSecondUnauthorizedAnswerIsReportedAndNotRetriedForever() {
        server.on("/token", Reply.of(200, TOKEN));
        server.on("/api/states/all", Reply.of(401, ""));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(true, 3, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Kind.UNAUTHORIZED, ex.kind());
        assertEquals(2, server.received("/api/states/all").size());
    }

    @Test
    void rejectedCredentialsAreReportedWithoutTheSecret() {
        server.on("/token", Reply.of(401, "{\"error\":\"invalid_client\",\"client_secret\":\"" + SECRET + "\"}"));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(true, 3, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Kind.UNAUTHORIZED, ex.kind());
        assertFalse(ex.getMessage().contains(SECRET));
        assertTrue(ex.getMessage().contains("OPENSKY_CLIENT_ID"));
        assertEquals(0, server.received("/api/states/all").size());
    }

    @Test
    void aRateLimitIsNotRetriedAndCarriesTheWaitOpenSkyAsksFor() {
        server.on("/api/states/all", Reply.of(429, "")
                .header("X-Rate-Limit-Retry-After-Seconds", "1234")
                .header("X-Rate-Limit-Remaining", "0"));

        OpenSkyClient client = client(false, 3, Duration.ofSeconds(2));
        OpenSkyException ex = assertThrows(OpenSkyException.class, () -> client.fetchStates(BOX));

        assertEquals(Kind.RATE_LIMITED, ex.kind());
        assertEquals(Duration.ofSeconds(1234), ex.retryAfter());
        assertEquals(1, server.received("/api/states/all").size());
        assertTrue(sleeps.isEmpty());
        assertEquals(0, client.rateLimitRemaining().orElseThrow());
    }

    @Test
    void aRateLimitWithoutAWaitUsesTheDefault() {
        server.on("/api/states/all", Reply.of(429, ""));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(false, 3, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Duration.ofMinutes(15), ex.retryAfter());
    }

    @Test
    void serverErrorsAreRetriedWithExponentialBackoff() {
        server.on("/api/states/all", Reply.of(503, ""), Reply.of(500, ""), Reply.of(200, ONE_STATE));

        OpenSkyClient client = client(false, 3, Duration.ofSeconds(2));
        OpenSkyStates states = client.fetchStates(BOX);

        assertEquals(1, states.states().size());
        assertEquals(3, server.received("/api/states/all").size());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), sleeps);
        assertEquals(3, client.callsLastOperation());
    }

    @Test
    void itGivesUpAfterTheConfiguredAttemptsAndCapsTheBackoff() {
        server.on("/api/states/all", Reply.of(500, ""));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(false, 4, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Kind.UNAVAILABLE, ex.kind());
        assertEquals(500, ex.httpStatus());
        assertEquals(4, ex.attempts());
        assertEquals(4, server.received("/api/states/all").size());
        // 100 ms, 200 ms, then capped at the 250 ms maximum.
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250)), sleeps);
    }

    @Test
    void aTimeoutIsRetriedAndThenReportedAsUnavailable() {
        server.on("/api/states/all", Reply.of(200, ONE_STATE).delayed(1500));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(false, 2, Duration.ofMillis(200)).fetchStates(BOX));

        assertEquals(Kind.UNAVAILABLE, ex.kind());
        assertEquals(2, ex.attempts());
        assertEquals(1, sleeps.size());
    }

    @Test
    void aTimeoutFollowedByAnAnswerSucceeds() {
        server.on("/api/states/all", Reply.of(200, ONE_STATE).delayed(1500), Reply.of(200, ONE_STATE));

        OpenSkyStates states = client(false, 3, Duration.ofMillis(200)).fetchStates(BOX);

        assertEquals(1, states.states().size());
        assertEquals(List.of(Duration.ofMillis(100)), sleeps);
    }

    @Test
    void aBadRequestIsNotRetried() {
        server.on("/api/states/all", Reply.of(400, ""));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(false, 3, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Kind.REJECTED, ex.kind());
        assertEquals(1, server.received("/api/states/all").size());
    }

    @Test
    void anAnswerThatIsNotTheDocumentedJsonIsAnInvalidResponse() {
        server.on("/api/states/all", Reply.of(200, "<html>maintenance</html>"));

        OpenSkyException ex = assertThrows(OpenSkyException.class,
                () -> client(false, 3, Duration.ofSeconds(2)).fetchStates(BOX));

        assertEquals(Kind.INVALID_RESPONSE, ex.kind());
    }

    @Test
    void departuresAreRequestedForTheAirportAndInterval() {
        server.on("/api/flights/departure", Reply.of(200, """
                [{"icao24":"800C6D","firstSeen":1794268800,"estDepartureAirport":"VABB","lastSeen":1794275400,
                  "estArrivalAirport":"VIDP","callsign":"IGO1454 ","estDepartureAirportHorizDistance":812}]"""));

        List<OpenSkyFlight> flights = client(false, 3, Duration.ofSeconds(2)).fetchDepartures("VABB",
                Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-09T23:59:59Z"));

        assertEquals(1, flights.size());
        assertEquals(new OpenSkyFlight("800c6d", 1794268800L, "VABB", 1794275400L, "VIDP", "IGO1454"),
                flights.get(0));
        assertEquals("airport=VABB&begin=1794182400&end=1794268799",
                server.received("/api/flights/departure").get(0).query());
    }

    @Test
    void noFlightsForThePeriodIsAnEmptyListNotAnError() {
        // OpenSky answers 404 when it has no flights for the period.
        List<OpenSkyFlight> flights = client(false, 3, Duration.ofSeconds(2)).fetchDepartures("VABB",
                Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-09T23:59:59Z"));

        assertTrue(flights.isEmpty());
        assertEquals(1, server.received("/api/flights/departure").size());
    }

    /** A clock tests can move. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
