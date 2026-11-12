package com.aerosync.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs ingestion in the background on the scheduler's threads, never on a
 * request thread, so a slow or unreachable external API cannot delay the
 * dashboard. The ticks are cheap: each feed decides for itself whether it is
 * due (poll interval, backoff after failures, rate-limit waits).
 *
 * <p>{@code fixedDelay} means a tick never overlaps the previous one. On
 * shutdown Spring waits for a running tick to finish
 * ({@code spring.task.scheduling.shutdown.*} in application.properties).</p>
 */
@Slf4j
@Component
public class IngestionScheduler {

    private final OpenSkyIngestionJob job;

    public IngestionScheduler(OpenSkyIngestionJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${aerosync.ingestion.tick-ms:15000}",
            initialDelayString = "${aerosync.ingestion.initial-delay-ms:20000}")
    public void liveStatesTick() {
        try {
            job.pollStatesIfDue();
        } catch (RuntimeException ex) {
            // Already handled and recorded inside the job; this only guards the scheduler thread.
            log.error("event=ingestion_tick_failed feed={} error=\"{}\"", OpenSkyIngestionJob.FEED_STATES,
                    ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${aerosync.ingestion.flight-legs-tick-ms:300000}",
            initialDelayString = "${aerosync.ingestion.flight-legs-initial-delay-ms:45000}")
    public void flightLegsTick() {
        try {
            job.importFlightLegsIfDue();
        } catch (RuntimeException ex) {
            log.error("event=ingestion_tick_failed feed={} error=\"{}\"", OpenSkyIngestionJob.FEED_FLIGHT_LEGS,
                    ex.getClass().getSimpleName());
        }
    }
}
