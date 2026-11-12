package com.aerosync.ingestion;

import java.time.Duration;

/**
 * How OpenSky ingestion runs. All values come from configuration
 * ({@code aerosync.opensky.*}), see {@link IngestionConfiguration}.
 *
 * @param enabled               master switch; ingestion also needs data mode LIVE or HYBRID
 * @param statesPollInterval    wanted time between live state polls
 * @param boundingBox           {@code minLat,minLon,maxLat,maxLon}; blank = computed from the airports
 * @param boundingBoxMargin     degrees added around the airports when the box is computed
 * @param maxStateAge           a state older than this (against the snapshot) is rejected
 * @param vicinityKm            radius within which an aircraft is linked to an airport
 * @param stateRetention        states not heard from for this long are deleted
 * @param flightLegsEnabled     whether the daily flight-leg feed runs
 * @param flightLegsInterval    time between checks for flight legs that are still to import
 * @param flightLegsLookbackDays how many past UTC days are imported
 * @param flightLegsMaxCalls    API calls one flight-leg cycle may make
 * @param dailyCredits          API credits per day to plan with; 0 = OpenSky's documented
 *                              default for the account type
 * @param failureBackoff        wait after a failed cycle; doubles per further failure
 * @param maxFailureBackoff     upper limit of that wait
 */
public record IngestionSettings(
        boolean enabled,
        Duration statesPollInterval,
        String boundingBox,
        double boundingBoxMargin,
        Duration maxStateAge,
        double vicinityKm,
        Duration stateRetention,
        boolean flightLegsEnabled,
        Duration flightLegsInterval,
        int flightLegsLookbackDays,
        int flightLegsMaxCalls,
        int dailyCredits,
        Duration failureBackoff,
        Duration maxFailureBackoff) {

    /** OpenSky's documented daily credits per bucket: anonymous and standard account. */
    public static final int ANONYMOUS_DAILY_CREDITS = 400;
    public static final int STANDARD_DAILY_CREDITS = 4_000;
    /** Share of the daily credits ingestion plans to use, leaving room for retries. */
    static final double BUDGET_SHARE = 0.9;
    /**
     * Documented credit cost of one {@code /flights/*} query that touches one or
     * two day partitions (one UTC day per query here). Queries for the last 24
     * hours cost less (4); the higher price is planned with.
     */
    public static final int FLIGHTS_QUERY_CREDITS = 30;

    public IngestionSettings {
        if (statesPollInterval.isZero() || statesPollInterval.isNegative()) {
            throw new IllegalArgumentException("The states poll interval must be positive");
        }
        if (flightLegsLookbackDays < 1 || flightLegsLookbackDays > 7) {
            throw new IllegalArgumentException("The flight-leg lookback must be 1 to 7 days");
        }
        boundingBox = boundingBox == null ? "" : boundingBox.trim();
    }

    /** Credits per day ingestion may plan with for this account type. */
    public int creditsPerDay(boolean authenticated) {
        if (dailyCredits > 0) {
            return dailyCredits;
        }
        return authenticated ? STANDARD_DAILY_CREDITS : ANONYMOUS_DAILY_CREDITS;
    }

    /**
     * {@code /flights} queries per UTC day the credit budget can pay for.
     * OpenSky keeps a separate credit bucket for {@code /flights/*}, so this
     * budget does not compete with the live states feed.
     */
    public int flightLegCallsPerDay(boolean authenticated) {
        return (int) Math.floor(creditsPerDay(authenticated) * BUDGET_SHARE / FLIGHTS_QUERY_CREDITS);
    }

    /**
     * The poll interval actually used: the wanted interval, raised when the
     * credit budget cannot pay for it (a states request costs
     * {@code creditCost} credits; the budget is spread over 24 hours).
     */
    public Duration effectiveStatesInterval(int creditCost, boolean authenticated) {
        double affordablePollsPerDay = creditsPerDay(authenticated) * BUDGET_SHARE / Math.max(1, creditCost);
        long minimumSeconds = (long) Math.ceil(86_400.0 / Math.max(1.0, affordablePollsPerDay));
        return statesPollInterval.getSeconds() >= minimumSeconds
                ? statesPollInterval
                : Duration.ofSeconds(minimumSeconds);
    }
}
