package com.aerosync.monitoring;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.WeatherReport;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monitoring rules for flights of the {@code flights} table (the flights the
 * resolution engine works on). Pure: the same inputs give the same result.
 *
 * <p>Only flights that are still SCHEDULED and depart within the lookahead
 * are examined, and each flight gets at most one detection, in this order:</p>
 * <ol>
 *   <li>{@link #RULE_WEATHER}: a current <em>observation</em> at the origin
 *       airport scores at or above the threshold. Forecasts are never used.</li>
 *   <li>{@link #RULE_CREW_DUTY}: the recorded crew duty hours are at or above
 *       the alert level.</li>
 *   <li>{@link #RULE_DELAY_RISK}: the delay model's probability is at or
 *       above the threshold.</li>
 * </ol>
 *
 * <p>The delay minutes of these detections are planning assumptions (the
 * description says so); nothing is measured about a flight that has not left.</p>
 */
public final class LegacyFlightRules {

    public static final String RULE_WEATHER = "WEATHER_OBSERVED";
    public static final String RULE_CREW_DUTY = "CREW_DUTY_LIMIT";
    public static final String RULE_DELAY_RISK = "ML_DELAY_RISK";

    /** Assumed delay for moderate and for severe observed weather. */
    static final int WEATHER_DELAY_MODERATE = 45;
    static final int WEATHER_DELAY_SEVERE = 90;
    static final int CREW_DELAY = 60;
    static final int DELAY_RISK_DELAY = 30;
    /** An observation older than this lowers the confidence. */
    static final Duration AGEING_OBSERVATION = Duration.ofMinutes(60);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private LegacyFlightRules() {
    }

    /**
     * @param flights      flights to examine (already limited to the served data mode)
     * @param observations current weather observations by IATA code; an airport
     *                     without a current observation is simply absent
     * @param now          current time in the zone the flights' times are stored in
     * @param nowInstant   the same moment as an instant (age of observations)
     */
    public static List<Detection> detect(List<Flight> flights, Map<String, WeatherReport> observations,
                                         LocalDateTime now, Instant nowInstant, MonitoringSettings settings) {
        List<Detection> detections = new ArrayList<>();
        LocalDateTime horizon = now.plus(settings.lookahead());
        for (Flight flight : flights) {
            if (flight.getFlightId() == null || flight.getStatus() != Flight.FlightStatus.SCHEDULED) {
                continue;
            }
            LocalDateTime departure = flight.getScheduledDeparture();
            if (departure == null || departure.isBefore(now) || departure.isAfter(horizon)) {
                continue;
            }
            Detection detection = weather(flight, observations, nowInstant, settings);
            if (detection == null) {
                detection = crewDuty(flight, settings);
            }
            if (detection == null) {
                detection = delayRisk(flight, settings);
            }
            if (detection != null) {
                detections.add(detection);
            }
        }
        return detections;
    }

    private static Detection weather(Flight flight, Map<String, WeatherReport> observations, Instant now,
                                     MonitoringSettings settings) {
        String origin = code(flight.getOrigin());
        WeatherReport observation = origin == null ? null : observations.get(origin);
        if (observation == null || observation.getKind() != WeatherReport.Kind.OBSERVATION
                || observation.getSeverityScore() < settings.weatherMinScore()) {
            return null;
        }
        int score = observation.getSeverityScore();
        boolean severe = score >= 3;
        double confidence = severe ? 0.85 : 0.65;
        if (Duration.between(observation.getValidAt(), now).compareTo(AGEING_OBSERVATION) > 0) {
            confidence -= 0.10;
        }
        int delay = severe ? WEATHER_DELAY_SEVERE : WEATHER_DELAY_MODERATE;
        String conditions = observation.getDescription() == null ? "adverse weather" : observation.getDescription();
        String description = String.format(Locale.ROOT,
                "Observed weather at %s (%s, score %d of 3, observed %s UTC) may delay %s, due out %s. "
                        + "The %d min delay is a planning estimate.",
                origin, conditions, score, TIME.format(observation.getValidAt().atOffset(ZoneOffset.UTC)),
                flight.getFlightId(), TIME.format(flight.getScheduledDeparture()), delay);
        return detection(RULE_WEATHER, DisruptionType.WEATHER_DELAY, flight,
                SeverityRules.fromWeatherScore(score), confidence, delay, description);
    }

    private static Detection crewDuty(Flight flight, MonitoringSettings settings) {
        Double hours = flight.getCrewDutyHours();
        if (hours == null || hours < settings.crewDutyAlertHours()) {
            return null;
        }
        boolean atLimit = hours >= settings.crewDutyLimitHours();
        String description = String.format(Locale.ROOT,
                "The crew of %s has %.1f duty hours recorded, %s the %.0f h limit. A replacement crew may be "
                        + "needed before the %s departure. The %d min delay is a planning estimate.",
                flight.getFlightId(), hours, atLimit ? "at or over" : "close to",
                settings.crewDutyLimitHours(), TIME.format(flight.getScheduledDeparture()), CREW_DELAY);
        return detection(RULE_CREW_DUTY, DisruptionType.CREW_TIMEOUT, flight,
                atLimit ? DisruptionSeverity.HIGH : DisruptionSeverity.MEDIUM, 0.80, CREW_DELAY, description);
    }

    private static Detection delayRisk(Flight flight, MonitoringSettings settings) {
        Double probability = flight.getDelayProbability();
        if (probability == null || probability < settings.delayRiskThreshold() || probability > 1.0) {
            return null;
        }
        String description = String.format(Locale.ROOT,
                "The delay model gives %s a %d%% delay probability for its %s departure. The %d min delay "
                        + "is a planning estimate, not a predicted length.",
                flight.getFlightId(), Math.round(probability * 100), TIME.format(flight.getScheduledDeparture()),
                DELAY_RISK_DELAY);
        return detection(RULE_DELAY_RISK, DisruptionType.FLIGHT_DELAY, flight,
                probability >= 0.90 ? DisruptionSeverity.HIGH : DisruptionSeverity.MEDIUM,
                probability, DELAY_RISK_DELAY, description);
    }

    private static Detection detection(String rule, DisruptionType type, Flight flight,
                                       DisruptionSeverity severity, double confidence, int delay,
                                       String description) {
        String key = rule + "|flight:" + flight.getFlightId() + "|" + KEY_TIME.format(flight.getScheduledDeparture());
        return new Detection(key, rule, type, flight.getFlightId(), null,
                code(flight.getOrigin()), code(flight.getDestination()),
                flight.getDataOrigin() != null ? flight.getDataOrigin() : DataOrigin.SYNTHETIC,
                severity, confidence, delay, description);
    }

    /** Three-letter upper-case code, or null. */
    private static String code(String value) {
        if (value == null) {
            return null;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        return code.matches("[A-Z]{3}") ? code : null;
    }
}
