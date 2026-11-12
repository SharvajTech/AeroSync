package com.aerosync.monitoring;

import java.time.Instant;

/**
 * What one monitoring cycle did.
 *
 * @param detected   potential disruptions the rules found this cycle
 * @param opened     new disruptions stored
 * @param refreshed  open disruptions seen again and updated
 * @param suppressed detections not stored: the flight already has an open
 *                   disruption, the event was closed recently, or the
 *                   per-cycle limit was reached
 * @param cleared    monitor disruptions closed because they are no longer detected
 * @param error      why the cycle failed; null when it completed
 */
public record MonitorCycleResult(
        Instant ranAt,
        long durationMs,
        int detected,
        int opened,
        int refreshed,
        int suppressed,
        int cleared,
        String error) {
}
