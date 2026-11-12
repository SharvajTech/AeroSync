package com.aerosync.monitoring;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.Disruption.DisruptionSeverity;
import com.aerosync.model.Disruption.DisruptionType;
import com.aerosync.model.Flight;
import com.aerosync.model.WeatherReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The monitoring rules for flights of the {@code flights} table. */
class LegacyFlightRulesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final Instant NOW_INSTANT = Instant.parse("2026-11-10T06:30:00Z");
    private static final MonitoringSettings SETTINGS = MonitoringSettings.defaults();

    static Flight flight(String id, String origin, LocalDateTime departure) {
        return Flight.builder().flightId(id).origin(origin).destination("DEL")
                .scheduledDeparture(departure).status(Flight.FlightStatus.SCHEDULED)
                .crewDutyHours(6.0).delayProbability(0.2).build();
    }

    static WeatherReport report(WeatherReport.Kind kind, int score, String description, Instant validAt) {
        return WeatherReport.builder().kind(kind).severityScore(score).description(description)
                .validAt(validAt).fetchedAt(validAt).build();
    }

    private static Map<String, WeatherReport> severeAtMumbai() {
        return Map.of("BOM", report(WeatherReport.Kind.OBSERVATION, 3, "thunderstorm with heavy rain",
                NOW_INSTANT.minus(Duration.ofMinutes(10))));
    }

    @Test
    void observedSevereWeatherAtTheOriginRaisesAWeatherDisruption() {
        List<Detection> detections = LegacyFlightRules.detect(
                List.of(flight("AI-101", "BOM", NOW.plusMinutes(90))), severeAtMumbai(), NOW, NOW_INSTANT, SETTINGS);

        assertEquals(1, detections.size());
        Detection detection = detections.get(0);
        assertEquals(LegacyFlightRules.RULE_WEATHER, detection.rule());
        assertEquals(DisruptionType.WEATHER_DELAY, detection.type());
        assertEquals(DisruptionSeverity.HIGH, detection.severity());
        assertEquals(0.85, detection.confidence());
        assertEquals(90, detection.delayMinutes());
        assertEquals("AI-101", detection.flightId());
        assertNull(detection.flightInstanceId());
        assertEquals("BOM", detection.originAirport());
        assertEquals("DEL", detection.destinationAirport());
        assertEquals(DataOrigin.SYNTHETIC, detection.dataOrigin());       // no origin recorded: synthetic
        assertEquals("WEATHER_OBSERVED|flight:AI-101|2026-11-10T13:30", detection.detectionKey());
        assertTrue(detection.description().contains("thunderstorm with heavy rain"), detection.description());
        assertTrue(detection.description().contains("planning estimate"), detection.description());
        assertTrue(detection.description().length() <= Detection.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void moderateAndAgeingObservationsLowerSeverityAndConfidence() {
        Map<String, WeatherReport> moderate = Map.of("BOM", report(WeatherReport.Kind.OBSERVATION, 2,
                "moderate rain", NOW_INSTANT.minus(Duration.ofMinutes(75))));

        Detection detection = LegacyFlightRules.detect(
                List.of(flight("AI-101", "BOM", NOW.plusMinutes(30))), moderate, NOW, NOW_INSTANT, SETTINGS).get(0);

        assertEquals(DisruptionSeverity.MEDIUM, detection.severity());
        assertEquals(0.55, detection.confidence());
        assertEquals(45, detection.delayMinutes());
    }

    @Test
    void aForecastOrLightWeatherOrAnotherAirportRaisesNothing() {
        Flight flight = flight("AI-101", "BOM", NOW.plusMinutes(60));
        Map<String, WeatherReport> forecast = Map.of("BOM",
                report(WeatherReport.Kind.FORECAST, 3, "thunderstorm", NOW_INSTANT.plus(Duration.ofHours(1))));
        Map<String, WeatherReport> light = Map.of("BOM",
                report(WeatherReport.Kind.OBSERVATION, 1, "light rain", NOW_INSTANT));
        Map<String, WeatherReport> elsewhere = Map.of("DEL",
                report(WeatherReport.Kind.OBSERVATION, 3, "dense fog", NOW_INSTANT));

        assertTrue(LegacyFlightRules.detect(List.of(flight), forecast, NOW, NOW_INSTANT, SETTINGS).isEmpty());
        assertTrue(LegacyFlightRules.detect(List.of(flight), light, NOW, NOW_INSTANT, SETTINGS).isEmpty());
        assertTrue(LegacyFlightRules.detect(List.of(flight), elsewhere, NOW, NOW_INSTANT, SETTINGS).isEmpty());
        assertTrue(LegacyFlightRules.detect(List.of(flight), Map.of(), NOW, NOW_INSTANT, SETTINGS).isEmpty());
    }

    @Test
    void onlyScheduledFlightsInsideTheLookaheadAreWatched() {
        Flight departed = flight("AI-1", "BOM", NOW.plusMinutes(30));
        departed.setStatus(Flight.FlightStatus.DEPARTED);
        Flight alreadyDisrupted = flight("AI-2", "BOM", NOW.plusMinutes(30));
        alreadyDisrupted.setStatus(Flight.FlightStatus.DISRUPTED);
        Flight past = flight("AI-3", "BOM", NOW.minusMinutes(5));
        Flight tooFar = flight("AI-4", "BOM", NOW.plusHours(3).plusMinutes(1));
        Flight noTime = flight("AI-5", "BOM", null);
        Flight inWindow = flight("AI-6", "BOM", NOW.plusHours(3));

        List<Detection> detections = LegacyFlightRules.detect(
                List.of(departed, alreadyDisrupted, past, tooFar, noTime, inWindow),
                severeAtMumbai(), NOW, NOW_INSTANT, SETTINGS);

        assertEquals(List.of("AI-6"), detections.stream().map(Detection::flightId).toList());
    }

    @Test
    void crewDutyAndDelayRiskAreFlaggedAtTheirThresholds() {
        Flight nearLimit = flight("AI-10", "DEL", NOW.plusMinutes(60));
        nearLimit.setCrewDutyHours(11.5);
        Flight overLimit = flight("AI-11", "DEL", NOW.plusMinutes(60));
        overLimit.setCrewDutyHours(12.0);
        Flight belowAlert = flight("AI-12", "DEL", NOW.plusMinutes(60));
        belowAlert.setCrewDutyHours(10.9);
        Flight risky = flight("AI-13", "DEL", NOW.plusMinutes(60));
        risky.setDelayProbability(0.86);
        Flight veryRisky = flight("AI-14", "DEL", NOW.plusMinutes(60));
        veryRisky.setDelayProbability(0.93);
        Flight belowThreshold = flight("AI-15", "DEL", NOW.plusMinutes(60));
        belowThreshold.setDelayProbability(0.79);
        Flight notPredicted = flight("AI-16", "DEL", NOW.plusMinutes(60));
        notPredicted.setDelayProbability(null);

        List<Detection> detections = LegacyFlightRules.detect(
                List.of(nearLimit, overLimit, belowAlert, risky, veryRisky, belowThreshold, notPredicted),
                Map.of(), NOW, NOW_INSTANT, SETTINGS);

        assertEquals(List.of("AI-10", "AI-11", "AI-13", "AI-14"),
                detections.stream().map(Detection::flightId).toList());
        assertEquals(DisruptionType.CREW_TIMEOUT, detections.get(0).type());
        assertEquals(DisruptionSeverity.MEDIUM, detections.get(0).severity());
        assertEquals(DisruptionSeverity.HIGH, detections.get(1).severity());
        assertEquals(DisruptionType.FLIGHT_DELAY, detections.get(2).type());
        assertEquals(LegacyFlightRules.RULE_DELAY_RISK, detections.get(2).rule());
        assertEquals(0.86, detections.get(2).confidence());
        assertEquals(DisruptionSeverity.MEDIUM, detections.get(2).severity());
        assertEquals(DisruptionSeverity.HIGH, detections.get(3).severity());
        assertTrue(detections.get(2).description().contains("86%"), detections.get(2).description());
    }

    @Test
    void aFlightGetsOneDetectionWithWeatherFirst() {
        Flight flight = flight("AI-101", "BOM", NOW.plusMinutes(60));
        flight.setCrewDutyHours(11.8);
        flight.setDelayProbability(0.95);
        flight.setDataOrigin(DataOrigin.MANUAL);

        List<Detection> withWeather = LegacyFlightRules.detect(
                List.of(flight), severeAtMumbai(), NOW, NOW_INSTANT, SETTINGS);
        List<Detection> withoutWeather = LegacyFlightRules.detect(
                List.of(flight), Map.of(), NOW, NOW_INSTANT, SETTINGS);

        assertEquals(1, withWeather.size());
        assertEquals(LegacyFlightRules.RULE_WEATHER, withWeather.get(0).rule());
        assertEquals(DataOrigin.MANUAL, withWeather.get(0).dataOrigin());
        assertEquals(1, withoutWeather.size());
        assertEquals(LegacyFlightRules.RULE_CREW_DUTY, withoutWeather.get(0).rule());
    }
}
