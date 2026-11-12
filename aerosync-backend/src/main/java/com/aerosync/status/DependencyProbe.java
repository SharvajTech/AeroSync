package com.aerosync.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Connectivity checks of the backend's own dependencies: the database and
 * the ML service. External APIs are not probed here; their health is read
 * from the last ingestion attempt, so a status request costs no API credits.
 */
@Component
public class DependencyProbe {

    /** Result of a check. {@code detail} is a short reason when down; never a stack trace or URL. */
    public record Check(boolean up, Instant checkedAt, String detail) {
    }

    private static final Duration ML_CACHE = Duration.ofSeconds(30);

    @PersistenceContext
    private EntityManager entityManager;

    private final String mlServiceUrl;
    private final Duration mlTimeout;
    private final Clock clock;
    private final HttpClient http;

    private volatile Check mlCheck;

    public DependencyProbe(@Value("${ml.service.url}") String mlServiceUrl,
                           @Value("${aerosync.status.ml-timeout-seconds:2}") long mlTimeoutSeconds,
                           Clock clock) {
        this.mlServiceUrl = mlServiceUrl.endsWith("/")
                ? mlServiceUrl.substring(0, mlServiceUrl.length() - 1) : mlServiceUrl;
        this.mlTimeout = Duration.ofSeconds(mlTimeoutSeconds);
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(this.mlTimeout).build();
    }

    /** Runs {@code SELECT 1}. Not transactional on purpose: a database that is down must be reported, not thrown. */
    public Check database() {
        Instant now = clock.instant();
        try {
            entityManager.createNativeQuery("SELECT 1").getSingleResult();
            return new Check(true, now, null);
        } catch (RuntimeException ex) {
            return new Check(false, now, "The database did not answer");
        }
    }

    /** Calls the ML service's {@code /health}; the result is reused for 30 seconds. */
    public Check mlService() {
        Check cached = mlCheck;
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.checkedAt().plus(ML_CACHE))) {
            return cached;
        }
        Check result;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(mlServiceUrl + "/health"))
                    .timeout(mlTimeout).GET().build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            result = status == 200 ? new Check(true, now, null)
                    : new Check(false, now, "The ML service answered HTTP " + status);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = new Check(false, now, "The check was interrupted");
        } catch (Exception ex) {
            result = new Check(false, now, "The ML service did not answer");
        }
        mlCheck = result;
        return result;
    }
}
