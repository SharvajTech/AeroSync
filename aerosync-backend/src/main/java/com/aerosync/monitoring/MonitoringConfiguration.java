package com.aerosync.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Reads the monitor's thresholds from configuration ({@code aerosync.monitoring.*}). */
@Configuration
public class MonitoringConfiguration {

    @Bean
    public MonitoringSettings monitoringSettings(
            @Value("${aerosync.monitoring.lookahead-minutes:180}") long lookaheadMinutes,
            @Value("${aerosync.monitoring.weather-min-score:2}") int weatherMinScore,
            @Value("${aerosync.monitoring.delay-risk-threshold:0.80}") double delayRiskThreshold,
            @Value("${aerosync.monitoring.crew-duty-alert-hours:11.0}") double crewDutyAlertHours,
            @Value("${aerosync.monitoring.crew-duty-limit-hours:12.0}") double crewDutyLimitHours,
            @Value("${aerosync.monitoring.network.min-delay-minutes:15}") int networkMinDelay,
            @Value("${aerosync.monitoring.network.lookback-hours:12}") long networkLookbackHours,
            @Value("${aerosync.monitoring.network.lookahead-hours:6}") long networkLookaheadHours,
            @Value("${aerosync.monitoring.network.max-depth:4}") int maxPropagationDepth,
            @Value("${aerosync.monitoring.max-new-per-cycle:25}") int maxNewPerCycle,
            @Value("${aerosync.monitoring.reopen-cooldown-minutes:360}") long reopenCooldownMinutes,
            @Value("${aerosync.monitoring.clear-after-minutes:10}") long clearAfterMinutes) {
        return new MonitoringSettings(Duration.ofMinutes(lookaheadMinutes), weatherMinScore, delayRiskThreshold,
                crewDutyAlertHours, crewDutyLimitHours, networkMinDelay, Duration.ofHours(networkLookbackHours),
                Duration.ofHours(networkLookaheadHours), maxPropagationDepth, maxNewPerCycle,
                Duration.ofMinutes(reopenCooldownMinutes), Duration.ofMinutes(clearAfterMinutes));
    }
}
