package com.aerosync.monitoring;

import com.aerosync.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background schedule of the weather refresh and the disruption monitor.
 * Both run on the scheduler's threads ({@code fixedDelay}: a run never
 * overlaps the previous one) and are stopped cleanly on shutdown.
 */
@Slf4j
@Component
public class BackgroundJobs {

    private final WeatherService weatherService;
    private final DisruptionMonitorService monitor;
    private final boolean monitoringEnabled;

    public BackgroundJobs(WeatherService weatherService, DisruptionMonitorService monitor,
                          @Value("${aerosync.monitoring.enabled:true}") boolean monitoringEnabled) {
        this.weatherService = weatherService;
        this.monitor = monitor;
        this.monitoringEnabled = monitoringEnabled;
    }

    public boolean monitoringEnabled() {
        return monitoringEnabled;
    }

    /** Refreshes weather reports that are due (does nothing without an API key). */
    @Scheduled(fixedDelayString = "${aerosync.weather.tick-ms:300000}",
            initialDelayString = "${aerosync.weather.initial-delay-ms:30000}")
    public void weatherTick() {
        try {
            weatherService.refreshDue();
        } catch (RuntimeException ex) {
            log.error("event=weather_tick_failed error={}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${aerosync.monitoring.interval-ms:120000}",
            initialDelayString = "${aerosync.monitoring.initial-delay-ms:60000}")
    public void monitorTick() {
        if (monitoringEnabled) {
            monitor.runCycle();
        }
    }
}
