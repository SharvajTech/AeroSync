package com.aerosync.monitoring;

import com.aerosync.datasource.DataSourcePolicy;
import com.aerosync.model.Disruption;
import com.aerosync.model.Disruption.DisruptionStatus;
import com.aerosync.model.Flight;
import com.aerosync.repository.DisruptionRepository;
import com.aerosync.repository.FlightRepository;
import com.aerosync.service.DisruptionService;
import com.aerosync.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The disruption detection pipeline: read current data → run the rules →
 * store what is new → close what is gone.
 *
 * <ul>
 *   <li><b>Detects only.</b> A detected disruption is stored with status
 *       DETECTED and waits for a controller. Nothing is proposed, approved or
 *       changed on a flight here: the existing propose → approve → execute
 *       workflow is the only way anything is executed.</li>
 *   <li><b>No duplicates.</b> Every detection has a key for the event. An
 *       open disruption with that key is refreshed instead of opened again
 *       (a unique index backs this in PostgreSQL), a flight of the
 *       {@code flights} table that already has an open disruption gets no
 *       second one, and an event closed recently is not raised again until
 *       the cooldown has passed.</li>
 *   <li><b>Closes what is gone.</b> A disruption the monitor opened that is
 *       still DETECTED and is no longer found is closed with a note. One a
 *       controller has started working on is never closed automatically.</li>
 * </ul>
 */
@Slf4j
@Service
public class DisruptionMonitorService {

    static final String CLEARED_NOTE = "Closed by the monitor: the condition is no longer detected.";

    private final FlightRepository flightRepository;
    private final DisruptionRepository disruptionRepository;
    private final WeatherService weatherService;
    private final NetworkMonitorReader networkReader;
    private final DataSourcePolicy dataSourcePolicy;
    private final MonitoringSettings settings;
    private final Clock clock;

    private volatile MonitorCycleResult lastResult;

    public DisruptionMonitorService(FlightRepository flightRepository, DisruptionRepository disruptionRepository,
                                    WeatherService weatherService, NetworkMonitorReader networkReader,
                                    DataSourcePolicy dataSourcePolicy, MonitoringSettings settings, Clock clock) {
        this.flightRepository = flightRepository;
        this.disruptionRepository = disruptionRepository;
        this.weatherService = weatherService;
        this.networkReader = networkReader;
        this.dataSourcePolicy = dataSourcePolicy;
        this.settings = settings;
        this.clock = clock;
    }

    /** The outcome of the most recent cycle; null before the first one. */
    public MonitorCycleResult lastResult() {
        return lastResult;
    }

    public MonitoringSettings settings() {
        return settings;
    }

    /** Runs one monitoring cycle. Never throws: a failure is reported in the result. */
    public synchronized MonitorCycleResult runCycle() {
        Instant started = clock.instant();
        MonitorCycleResult result;
        try {
            result = cycle(started);
            log.info("event=disruption_monitor_cycle detected={} opened={} refreshed={} suppressed={} cleared={} "
                            + "duration_ms={}", result.detected(), result.opened(), result.refreshed(),
                    result.suppressed(), result.cleared(), result.durationMs());
        } catch (RuntimeException ex) {
            result = new MonitorCycleResult(started, millisSince(started), 0, 0, 0, 0, 0,
                    ex.getClass().getSimpleName());
            log.error("event=disruption_monitor_failed error={}", ex.getClass().getSimpleName(), ex);
        }
        lastResult = result;
        return result;
    }

    private MonitorCycleResult cycle(Instant started) {
        // Disruption and flight times are stored as local date-times in the server's zone.
        LocalDateTime now = LocalDateTime.ofInstant(started, ZoneId.systemDefault());

        List<Detection> detections = new ArrayList<>();
        List<Flight> flights = flightRepository.findAll().stream().filter(dataSourcePolicy::serves).toList();
        detections.addAll(LegacyFlightRules.detect(
                flights, weatherService.storedCurrentObservations(), now, started, settings));
        detections.addAll(networkReader.detect(started, settings));
        // Most severe first, so the per-cycle limit keeps the important ones.
        detections.sort(Comparator
                .comparing((Detection d) -> d.severity().ordinal()).reversed()
                .thenComparing(Detection::detectionKey));

        Map<String, Disruption> openByKey = new HashMap<>();
        Set<String> flightsWithOpenDisruption = new HashSet<>();
        for (Disruption open : disruptionRepository.findByStatusIn(Disruption.OPEN_STATUSES)) {
            if (open.getDetectionKey() != null) {
                openByKey.put(open.getDetectionKey(), open);
            }
            if (open.getFlightInstanceId() == null && open.getFlightId() != null) {
                flightsWithOpenDisruption.add(open.getFlightId());
            }
        }

        int opened = 0;
        int refreshed = 0;
        int suppressed = 0;
        Set<String> detectedKeys = new HashSet<>();
        for (Detection detection : detections) {
            detectedKeys.add(detection.detectionKey());
            Disruption open = openByKey.get(detection.detectionKey());
            if (open != null) {
                refresh(open, detection, now);
                refreshed++;
                continue;
            }
            boolean legacyFlight = detection.flightInstanceId() == null;
            if (legacyFlight && flightsWithOpenDisruption.contains(detection.flightId())) {
                suppressed++;       // the flight is already being handled
                continue;
            }
            if (opened >= settings.maxNewPerCycle()
                    || disruptionRepository.existsByDetectionKeyAndResolvedAtAfter(
                            detection.detectionKey(), now.minus(settings.reopenCooldown()))) {
                suppressed++;
                continue;
            }
            if (open(detection, now)) {
                opened++;
                if (legacyFlight) {
                    flightsWithOpenDisruption.add(detection.flightId());
                }
            } else {
                suppressed++;       // another instance stored the same event first
            }
        }

        int cleared = 0;
        LocalDateTime clearBefore = now.minus(settings.clearAfter());
        for (Disruption disruption : disruptionRepository.findByDetectionSourceAndStatusIn(
                DisruptionService.SOURCE_MONITOR, List.of(DisruptionStatus.DETECTED))) {
            if (detectedKeys.contains(disruption.getDetectionKey())) {
                continue;
            }
            LocalDateTime lastSeen = disruption.getLastObservedAt() != null
                    ? disruption.getLastObservedAt() : disruption.getDetectedAt();
            if (lastSeen == null || lastSeen.isBefore(clearBefore)) {
                disruption.setStatus(DisruptionStatus.RESOLVED);
                disruption.setResolvedAt(now);
                disruption.setResolutionNote(CLEARED_NOTE);
                disruptionRepository.save(disruption);
                cleared++;
            }
        }
        return new MonitorCycleResult(started, millisSince(started), detections.size(), opened, refreshed,
                suppressed, cleared, null);
    }

    /** @return false when the database refused it as a duplicate of an open event */
    private boolean open(Detection detection, LocalDateTime now) {
        try {
            disruptionRepository.save(Disruption.builder()
                    .flightId(detection.flightId())
                    .flightInstanceId(detection.flightInstanceId())
                    .type(detection.type())
                    .status(DisruptionStatus.DETECTED)
                    .description(detection.description())
                    .delayMinutes(detection.delayMinutes())
                    .detectedAt(now)
                    .lastObservedAt(now)
                    .severity(detection.severity())
                    .confidence(detection.confidence())
                    .detectionSource(DisruptionService.SOURCE_MONITOR)
                    .detectionRule(detection.rule())
                    .detectionKey(detection.detectionKey())
                    .dataOrigin(detection.dataOrigin())
                    .originAirport(detection.originAirport())
                    .destinationAirport(detection.destinationAirport())
                    .build());
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    /** Seen again: note it, and keep the figures current while nobody has started on it. */
    private void refresh(Disruption open, Detection detection, LocalDateTime now) {
        open.setLastObservedAt(now);
        if (open.getStatus() == DisruptionStatus.DETECTED) {
            open.setSeverity(detection.severity());
            open.setConfidence(detection.confidence());
            open.setDelayMinutes(detection.delayMinutes());
            open.setDescription(detection.description());
        }
        disruptionRepository.save(open);
    }

    private long millisSince(Instant started) {
        return java.time.Duration.between(started, clock.instant()).toMillis();
    }
}
