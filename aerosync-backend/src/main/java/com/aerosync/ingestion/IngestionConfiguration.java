package com.aerosync.ingestion;

import com.aerosync.ingestion.opensky.OpenSkyClient;
import com.aerosync.ingestion.opensky.OpenSkySettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires OpenSky ingestion from configuration. Credentials are read from the
 * environment only ({@code OPENSKY_CLIENT_ID}, {@code OPENSKY_CLIENT_SECRET});
 * there are no defaults and they are never logged.
 */
@Configuration
public class IngestionConfiguration {

    @Bean
    public Clock ingestionClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenSkySettings openSkySettings(
            @Value("${aerosync.opensky.base-url:}") String baseUrl,
            @Value("${aerosync.opensky.token-url:}") String tokenUrl,
            @Value("${aerosync.opensky.client-id:}") String clientId,
            @Value("${aerosync.opensky.client-secret:}") String clientSecret,
            @Value("${aerosync.opensky.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${aerosync.opensky.request-timeout-seconds:15}") long requestTimeoutSeconds,
            @Value("${aerosync.opensky.max-attempts:3}") int maxAttempts,
            @Value("${aerosync.opensky.retry-backoff-ms:1000}") long retryBackoffMs,
            @Value("${aerosync.opensky.retry-max-backoff-ms:15000}") long retryMaxBackoffMs) {
        return new OpenSkySettings(baseUrl, tokenUrl, clientId, clientSecret,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds),
                maxAttempts, Duration.ofMillis(retryBackoffMs), Duration.ofMillis(retryMaxBackoffMs));
    }

    @Bean
    public OpenSkyClient openSkyClient(OpenSkySettings settings) {
        return new OpenSkyClient(settings);
    }

    @Bean
    public CallsignMapper callsignMapper(@Value("${aerosync.opensky.airline-codes:}") String extraAirlineCodes) {
        return CallsignMapper.withExtra(extraAirlineCodes);
    }

    @Bean
    public IngestionSettings ingestionSettings(
            @Value("${aerosync.opensky.enabled:true}") boolean enabled,
            @Value("${aerosync.opensky.states.poll-seconds:300}") long statesPollSeconds,
            @Value("${aerosync.opensky.states.bounding-box:}") String boundingBox,
            @Value("${aerosync.opensky.states.bounding-box-margin-degrees:0.75}") double boundingBoxMargin,
            @Value("${aerosync.opensky.states.max-age-seconds:300}") long maxStateAgeSeconds,
            @Value("${aerosync.opensky.states.vicinity-km:60}") double vicinityKm,
            @Value("${aerosync.opensky.states.retention-hours:6}") long retentionHours,
            @Value("${aerosync.opensky.flight-legs.enabled:true}") boolean flightLegsEnabled,
            @Value("${aerosync.opensky.flight-legs.check-minutes:60}") long flightLegsCheckMinutes,
            @Value("${aerosync.opensky.flight-legs.lookback-days:2}") int lookbackDays,
            @Value("${aerosync.opensky.flight-legs.max-calls-per-cycle:20}") int maxCallsPerCycle,
            @Value("${aerosync.opensky.daily-credits:0}") int dailyCredits,
            @Value("${aerosync.opensky.failure-backoff-seconds:60}") long failureBackoffSeconds,
            @Value("${aerosync.opensky.failure-max-backoff-seconds:1800}") long failureMaxBackoffSeconds) {
        return new IngestionSettings(enabled, Duration.ofSeconds(statesPollSeconds), boundingBox,
                boundingBoxMargin, Duration.ofSeconds(maxStateAgeSeconds), vicinityKm,
                Duration.ofHours(retentionHours), flightLegsEnabled, Duration.ofMinutes(flightLegsCheckMinutes),
                lookbackDays, maxCallsPerCycle, dailyCredits, Duration.ofSeconds(failureBackoffSeconds),
                Duration.ofSeconds(failureMaxBackoffSeconds));
    }
}
