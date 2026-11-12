package com.aerosync.ingestion.opensky;

import com.aerosync.ingestion.opensky.OpenSkyException.Kind;
import com.aerosync.security.LogRedaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Client of the OpenSky Network REST API
 * (https://openskynetwork.github.io/opensky-api/rest.html).
 *
 * <ul>
 *   <li><b>Authentication:</b> OAuth2 client credentials (OpenSky no longer
 *       accepts basic authentication). The 30-minute token is cached and
 *       renewed shortly before it expires; a 401 renews it once and retries.
 *       Without credentials requests are anonymous.</li>
 *   <li><b>Rate limits:</b> a 429 is never retried here. It is reported with
 *       the wait OpenSky asks for ({@code X-Rate-Limit-Retry-After-Seconds}) so
 *       the caller can stay away until then.</li>
 *   <li><b>Transient failures</b> (timeouts, connection errors, 5xx) are
 *       retried with exponential backoff up to {@code maxAttempts}.</li>
 *   <li>Secrets and tokens never appear in logs or exception messages.</li>
 * </ul>
 *
 * Not a Spring bean itself, so it can be tested against a local HTTP server.
 */
public class OpenSkyClient {

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final String RATE_LIMIT_REMAINING_HEADER = "X-Rate-Limit-Remaining";
    static final String RATE_LIMIT_RETRY_HEADER = "X-Rate-Limit-Retry-After-Seconds";
    /** Used when a 429 carries no usable wait. */
    static final Duration DEFAULT_RATE_LIMIT_WAIT = Duration.ofMinutes(15);
    /** A token is renewed this long before it expires. */
    static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

    private final OpenSkySettings settings;
    private final HttpClient http;
    private final Clock clock;
    private final Sleeper sleeper;

    private final Object tokenLock = new Object();
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    private volatile Integer rateLimitRemaining;
    private volatile int callsLastOperation;

    public OpenSkyClient(OpenSkySettings settings) {
        this(settings,
                HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build(),
                Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()));
    }

    public OpenSkyClient(OpenSkySettings settings, HttpClient http, Clock clock, Sleeper sleeper) {
        this.settings = settings;
        this.http = http;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public boolean authenticated() {
        return settings.authenticated();
    }

    /** Credits left according to the last answer that reported them; empty if none did. */
    public Optional<Integer> rateLimitRemaining() {
        return Optional.ofNullable(rateLimitRemaining);
    }

    /** HTTP requests the last fetch made, including retries and token requests. */
    public int callsLastOperation() {
        return callsLastOperation;
    }

    /** Live state vectors inside a bounding box ({@code GET /states/all}). */
    public OpenSkyStates fetchStates(BoundingBox box) {
        callsLastOperation = 0;
        String body = get("/states/all?" + box.toQuery(), false);
        try {
            return OpenSkyResponseParser.parseStates(body);
        } catch (IllegalArgumentException ex) {
            throw new OpenSkyException(Kind.INVALID_RESPONSE, "OpenSky " + ex.getMessage(), 200, null,
                    callsLastOperation);
        }
    }

    /**
     * Flights that departed from an airport in {@code [begin, end]}
     * ({@code GET /flights/departure}). OpenSky fills this in a nightly batch
     * and answers 404 when it has nothing for the period; that is returned as
     * an empty list.
     *
     * @param icaoAirport ICAO code of the airport, for example {@code VABB}
     */
    public List<OpenSkyFlight> fetchDepartures(String icaoAirport, Instant begin, Instant end) {
        callsLastOperation = 0;
        String path = "/flights/departure?airport=" + URLEncoder.encode(icaoAirport, StandardCharsets.UTF_8)
                + "&begin=" + begin.getEpochSecond() + "&end=" + end.getEpochSecond();
        String body = get(path, true);
        if (body == null) {
            return List.of();
        }
        try {
            return OpenSkyResponseParser.parseFlights(body);
        } catch (IllegalArgumentException ex) {
            throw new OpenSkyException(Kind.INVALID_RESPONSE, "OpenSky " + ex.getMessage(), 200, null,
                    callsLastOperation);
        }
    }

    /**
     * @param notFoundIsEmpty when true a 404 returns null instead of failing
     * @return the response body of a 200 answer
     */
    private String get(String pathAndQuery, boolean notFoundIsEmpty) {
        URI uri = URI.create(settings.baseUrl() + pathAndQuery);
        boolean tokenRenewed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(settings.requestTimeout())
                    .header("Accept", "application/json")
                    .GET();
            if (settings.authenticated()) {
                request.header("Authorization", "Bearer " + currentToken());
            }

            HttpResponse<String> response;
            try {
                callsLastOperation++;
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException ex) {
                // Timeouts (HttpTimeoutException) and connection failures.
                if (attempt >= settings.maxAttempts()) {
                    throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky did not answer after " + attempt
                            + " attempt(s): " + describe(ex), 0, null, attempt);
                }
                waitBeforeRetry(attempt, describe(ex));
                continue;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky request interrupted", 0, null, attempt);
            }

            int status = response.statusCode();
            response.headers().firstValue(RATE_LIMIT_REMAINING_HEADER)
                    .map(OpenSkyClient::parseIntOrNull)
                    .ifPresent(remaining -> rateLimitRemaining = remaining);

            if (status == 200) {
                return response.body();
            }
            if (status == 404 && notFoundIsEmpty) {
                return null;
            }
            if (status == 429) {
                Duration wait = response.headers().firstValue(RATE_LIMIT_RETRY_HEADER)
                        .or(() -> response.headers().firstValue("Retry-After"))
                        .map(OpenSkyClient::parseIntOrNull)
                        .filter(seconds -> seconds > 0)
                        .map(seconds -> Duration.ofSeconds(seconds))
                        .orElse(DEFAULT_RATE_LIMIT_WAIT);
                throw new OpenSkyException(Kind.RATE_LIMITED, "OpenSky rate limit reached; retry after "
                        + wait.toSeconds() + " s", status, wait, attempt);
            }
            if (status == 401) {
                if (settings.authenticated() && !tokenRenewed) {
                    // Documented behaviour: the token expired. Renew once and retry.
                    invalidateToken();
                    tokenRenewed = true;
                    attempt--;      // not a failed attempt of the request itself
                    continue;
                }
                throw new OpenSkyException(Kind.UNAUTHORIZED, "OpenSky rejected the request as unauthorized",
                        status, null, attempt);
            }
            if (status >= 500) {
                if (attempt >= settings.maxAttempts()) {
                    throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky answered HTTP " + status + " after "
                            + attempt + " attempt(s)", status, null, attempt);
                }
                waitBeforeRetry(attempt, "HTTP " + status);
                continue;
            }
            // 400 (for example a state time older than one hour), 403 and other client errors.
            throw new OpenSkyException(Kind.REJECTED, "OpenSky rejected the request with HTTP " + status,
                    status, null, attempt);
        }
    }

    private void waitBeforeRetry(int attempt, String reason) {
        Duration wait = backoff(attempt);
        log.warn("event=opensky_retry attempt={} max_attempts={} wait_ms={} reason=\"{}\"",
                attempt, settings.maxAttempts(), wait.toMillis(), reason);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky request interrupted", 0, null, attempt);
        }
    }

    /** Wait before attempt {@code failedAttempt + 1}: initial × 2^(failedAttempt-1), capped. */
    Duration backoff(int failedAttempt) {
        long factor = 1L << Math.min(Math.max(failedAttempt - 1, 0), 20);
        Duration wait = settings.initialBackoff().multipliedBy(factor);
        return wait.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : wait;
    }

    // ── OAuth2 client credentials ───────────────────────────────────────────

    private String currentToken() {
        synchronized (tokenLock) {
            Instant now = clock.instant();
            if (accessToken == null || !now.isBefore(accessTokenExpiresAt.minus(TOKEN_EXPIRY_MARGIN))) {
                OpenSkyResponseParser.TokenResponse token = requestToken();
                accessToken = token.accessToken();
                accessTokenExpiresAt = now.plusSeconds(token.expiresInSeconds());
            }
            return accessToken;
        }
    }

    private void invalidateToken() {
        synchronized (tokenLock) {
            accessToken = null;
            accessTokenExpiresAt = Instant.EPOCH;
        }
    }

    private OpenSkyResponseParser.TokenResponse requestToken() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(settings.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(settings.clientSecret(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.tokenUrl()))
                .timeout(settings.requestTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            callsLastOperation++;
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky token service did not answer: " + describe(ex),
                    0, null, 1);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky token request interrupted", 0, null, 1);
        }
        int status = response.statusCode();
        if (status == 400 || status == 401 || status == 403) {
            // The body may echo the client id; do not include it.
            throw new OpenSkyException(Kind.UNAUTHORIZED, "OpenSky rejected the client credentials (HTTP "
                    + status + "); check OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET", status, null, 1);
        }
        if (status != 200) {
            throw new OpenSkyException(Kind.UNAVAILABLE, "OpenSky token service answered HTTP " + status,
                    status, null, 1);
        }
        try {
            return OpenSkyResponseParser.parseToken(response.body());
        } catch (IllegalArgumentException ex) {
            throw new OpenSkyException(Kind.INVALID_RESPONSE, "OpenSky " + ex.getMessage(), status, null, 1);
        }
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Exception class and message with anything credential-like removed. */
    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return ex.getClass().getSimpleName() + (message == null ? "" : ": " + LogRedaction.redact(message));
    }
}
