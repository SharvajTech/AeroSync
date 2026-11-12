package com.aerosync.monitoring;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionType;

import java.util.UUID;

/**
 * A potential disruption found by a monitoring rule, before it is stored.
 *
 * @param detectionKey     identifies the event (rule + flight + occurrence); the
 *                         monitor never opens two disruptions with the same key
 * @param rule             the rule that found it, for example {@code WEATHER_OBSERVED}
 * @param flightId         flight id of the {@code flights} table, or the flight
 *                         number of an operational-network flight
 * @param flightInstanceId set for operational-network flights (advisory disruptions)
 * @param confidence       0..1, how sure the rule is (see each rule)
 * @param delayMinutes     recorded, computed or assumed delay; the description says which
 */
public record Detection(
        String detectionKey,
        String rule,
        DisruptionType type,
        String flightId,
        UUID flightInstanceId,
        String originAirport,
        String destinationAirport,
        DataOrigin dataOrigin,
        DisruptionSeverity severity,
        double confidence,
        int delayMinutes,
        String description) {

    public static final int MAX_DESCRIPTION_LENGTH = 255;

    public Detection {
        confidence = SeverityRules.clampConfidence(confidence);
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH - 1) + "…";
        }
    }
}
