package com.aerosync.monitoring;

import com.aerosync.model.Disruption.DisruptionSeverity;

/**
 * How AeroSync grades a disruption. These are fixed, documented thresholds
 * (operational planning assumptions), not measurements.
 */
public final class SeverityRules {

    private SeverityRules() {
    }

    /** Under 30 min: LOW. Under 90: MEDIUM. Under 180: HIGH. Otherwise CRITICAL. */
    public static DisruptionSeverity fromDelayMinutes(int delayMinutes) {
        if (delayMinutes < 30) {
            return DisruptionSeverity.LOW;
        }
        if (delayMinutes < 90) {
            return DisruptionSeverity.MEDIUM;
        }
        if (delayMinutes < 180) {
            return DisruptionSeverity.HIGH;
        }
        return DisruptionSeverity.CRITICAL;
    }

    /** Weather score 0-3 (see WeatherService): 2 is MEDIUM, 3 is HIGH; below that LOW. */
    public static DisruptionSeverity fromWeatherScore(int score) {
        if (score >= 3) {
            return DisruptionSeverity.HIGH;
        }
        return score == 2 ? DisruptionSeverity.MEDIUM : DisruptionSeverity.LOW;
    }

    /** Keeps a confidence inside 0..1, rounded to two decimals. */
    public static double clampConfidence(double confidence) {
        double clamped = Math.max(0.0, Math.min(1.0, confidence));
        return Math.round(clamped * 100.0) / 100.0;
    }
}
