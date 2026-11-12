package com.aerosync.monitoring;

import java.time.Duration;

/**
 * Thresholds of the disruption monitor ({@code aerosync.monitoring.*}). They
 * are operational planning assumptions and are all configurable.
 *
 * @param lookahead            flights of the {@code flights} table departing within
 *                             this time are watched
 * @param weatherMinScore      observed weather score (0-3) at the origin from which a
 *                             weather disruption is raised
 * @param delayRiskThreshold   delay-model probability from which a flight is flagged
 * @param crewDutyAlertHours   recorded crew duty hours from which a crew disruption is raised
 * @param crewDutyLimitHours   the duty limit itself (severity HIGH at or above it)
 * @param networkMinDelay      recorded delay from which a network flight counts as late
 * @param networkLookback      late network flights that departed up to this long ago are followed
 * @param networkLookahead     network flights departing within this time are reported
 * @param maxPropagationDepth  how many dependency hops a delay is followed
 * @param maxNewPerCycle       new disruptions one cycle may open (the most severe first)
 * @param reopenCooldown       an event closed less than this long ago is not raised again
 * @param clearAfter           a monitor disruption no longer detected for this long is closed
 */
public record MonitoringSettings(
        Duration lookahead,
        int weatherMinScore,
        double delayRiskThreshold,
        double crewDutyAlertHours,
        double crewDutyLimitHours,
        int networkMinDelay,
        Duration networkLookback,
        Duration networkLookahead,
        int maxPropagationDepth,
        int maxNewPerCycle,
        Duration reopenCooldown,
        Duration clearAfter) {

    public static MonitoringSettings defaults() {
        return new MonitoringSettings(Duration.ofHours(3), 2, 0.80, 11.0, 12.0, 15,
                Duration.ofHours(12), Duration.ofHours(6), 4, 25, Duration.ofHours(6), Duration.ofMinutes(10));
    }
}
