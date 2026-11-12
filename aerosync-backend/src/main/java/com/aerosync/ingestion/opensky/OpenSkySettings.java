package com.aerosync.ingestion.opensky;

import java.time.Duration;

/**
 * Connection settings of the OpenSky Network REST API. Credentials come from
 * the environment ({@code OPENSKY_CLIENT_ID} / {@code OPENSKY_CLIENT_SECRET});
 * without them the API is used anonymously, with a much smaller daily budget.
 *
 * @param baseUrl        REST base URL, e.g. {@code https://opensky-network.org/api}
 * @param tokenUrl       OAuth2 token endpoint (client credentials flow)
 * @param clientId       OAuth2 client id; blank for anonymous access
 * @param clientSecret   OAuth2 client secret; never logged
 * @param connectTimeout TCP connect timeout
 * @param requestTimeout time allowed for one request
 * @param maxAttempts    attempts per request for transient failures (at least 1)
 * @param initialBackoff wait before the second attempt; doubles for each further attempt
 * @param maxBackoff     upper limit of the wait between attempts
 */
public record OpenSkySettings(
        String baseUrl,
        String tokenUrl,
        String clientId,
        String clientSecret,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff) {

    public static final String DEFAULT_BASE_URL = "https://opensky-network.org/api";
    public static final String DEFAULT_TOKEN_URL =
            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";

    public OpenSkySettings {
        baseUrl = stripTrailingSlash(blankToDefault(baseUrl, DEFAULT_BASE_URL));
        tokenUrl = blankToDefault(tokenUrl, DEFAULT_TOKEN_URL);
        clientId = clientId == null ? "" : clientId.trim();
        clientSecret = clientSecret == null ? "" : clientSecret.trim();
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (clientId.isEmpty() != clientSecret.isEmpty()) {
            throw new IllegalStateException(
                    "Set both OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET, or neither (anonymous access)");
        }
    }

    /** True when OAuth2 client credentials are configured. */
    public boolean authenticated() {
        return !clientId.isEmpty();
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "OpenSkySettings[baseUrl=" + baseUrl + ", authenticated=" + authenticated()
                + ", requestTimeout=" + requestTimeout + ", maxAttempts=" + maxAttempts + "]";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
