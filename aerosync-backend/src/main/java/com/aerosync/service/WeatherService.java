package com.aerosync.service;

import com.aerosync.datasource.DataSources;
import com.aerosync.dto.live.AirportWeatherDto;
import com.aerosync.dto.live.WeatherReportDto;
import com.aerosync.model.Airport;
import com.aerosync.model.WeatherReport;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.WeatherReportRepository;
import com.aerosync.weather.OpenWeatherClient;
import com.aerosync.weather.WeatherReading;
import com.aerosync.weather.WeatherUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Airport weather from OpenWeatherMap.
 *
 * <p>Stage 4 changes, keeping the existing methods and the 0-3 score:</p>
 * <ul>
 *   <li>Weather is looked up by the airport's coordinates (table
 *       {@code airports}), for every airport of the network, instead of by
 *       city name for eight of them.</li>
 *   <li>Every report is stored with the source's own timestamp
 *       ({@code weather_reports}), so the age of an observation is known and
 *       it survives a restart.</li>
 *   <li>Observations and forecasts are separate report kinds. Only
 *       observations feed delay prediction and disruption detection.</li>
 *   <li>An unavailable source is reported as unavailable. The score of 1 that
 *       {@link #getWeatherScore} returns in that case is the previous default
 *       and is kept for the delay model; callers that must know whether the
 *       value was measured use {@link #currentObservation}.</li>
 *   <li>Calls are limited: an observation is reused for
 *       {@code aerosync.weather.refresh-minutes}, and after a failure the
 *       source is left alone for a few minutes.</li>
 * </ul>
 */
@Slf4j
@Service
public class WeatherService {

    /** Score used by the delay model when no observation is available (unchanged from earlier stages). */
    public static final int DEFAULT_SCORE = 1;

    /** After a failed call the source is not tried again for this long. */
    static final Duration FAILURE_PAUSE = Duration.ofMinutes(5);
    private static final Duration RETENTION = Duration.ofDays(3);

    private final AirportRepository airportRepository;
    private final WeatherReportRepository reportRepository;
    private final OpenWeatherClient client;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Duration maxObservationAge;
    private final boolean forecastEnabled;
    private final Duration forecastRefreshInterval;
    private final int forecastSteps;

    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastAttemptAt;
    private volatile String lastError;
    private final Map<String, Instant> forecastFetchedAt = new ConcurrentHashMap<>();

    @Autowired
    public WeatherService(
            AirportRepository airportRepository,
            WeatherReportRepository reportRepository,
            Clock clock,
            @Value("${weather.api.key:}") String apiKey,
            @Value("${weather.api.url:https://api.openweathermap.org/data/2.5}") String apiUrl,
            @Value("${aerosync.weather.timeout-seconds:5}") long timeoutSeconds,
            @Value("${aerosync.weather.refresh-minutes:30}") long refreshMinutes,
            @Value("${aerosync.weather.max-observation-age-minutes:120}") long maxObservationAgeMinutes,
            @Value("${aerosync.weather.forecast.enabled:true}") boolean forecastEnabled,
            @Value("${aerosync.weather.forecast.refresh-minutes:180}") long forecastRefreshMinutes,
            @Value("${aerosync.weather.forecast.hours:12}") int forecastHours) {
        this(airportRepository, reportRepository,
                new OpenWeatherClient(apiUrl, apiKey, Duration.ofSeconds(timeoutSeconds)), clock,
                Duration.ofMinutes(refreshMinutes), Duration.ofMinutes(maxObservationAgeMinutes),
                forecastEnabled, Duration.ofMinutes(forecastRefreshMinutes), Math.max(1, forecastHours / 3));
    }

    public WeatherService(AirportRepository airportRepository, WeatherReportRepository reportRepository,
                          OpenWeatherClient client, Clock clock, Duration refreshInterval,
                          Duration maxObservationAge, boolean forecastEnabled,
                          Duration forecastRefreshInterval, int forecastSteps) {
        this.airportRepository = airportRepository;
        this.reportRepository = reportRepository;
        this.client = client;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
        this.maxObservationAge = maxObservationAge;
        this.forecastEnabled = forecastEnabled;
        this.forecastRefreshInterval = forecastRefreshInterval;
        this.forecastSteps = forecastSteps;
    }

    // ── Existing API (kept) ───────────────────────────────────────────────────

    /**
     * 0-3 score of the current observation at an airport, refreshed if it is
     * older than the refresh interval. {@link #DEFAULT_SCORE} when the airport
     * is unknown or no current observation is available.
     */
    public int getWeatherScore(String airportCode) {
        return currentObservation(airportCode).map(WeatherReport::getSeverityScore).orElse(DEFAULT_SCORE);
    }

    /**
     * Scores of the airports that have a current observation. Airports
     * without one are left out (Stage 4: they used to be reported as 1, which
     * could not be told apart from a measured value).
     */
    public Map<String, Integer> getWeatherForAllAirports() {
        Map<String, Integer> scores = new TreeMap<>();
        currentObservations().forEach((iata, report) -> scores.put(iata, report.getSeverityScore()));
        return scores;
    }

    // ── Stage 4 ───────────────────────────────────────────────────────────────

    /** True when an OpenWeatherMap API key is configured. */
    public boolean configured() {
        return client.configured();
    }

    /** The current observation at an airport: measured, and not older than the accepted age. */
    public Optional<WeatherReport> currentObservation(String airportCode) {
        if (airportCode == null) {
            return Optional.empty();
        }
        return airportRepository.findByIataCode(airportCode.trim().toUpperCase(Locale.ROOT))
                .flatMap(airport -> usable(observation(airport, true)));
    }

    /** Current observations of every airport that has one, keyed by IATA code. */
    public Map<String, WeatherReport> currentObservations() {
        Map<String, WeatherReport> result = new LinkedHashMap<>();
        for (Airport airport : airportRepository.findAllByOrderByIataCodeAsc()) {
            usable(observation(airport, true)).ifPresent(report -> result.put(airport.getIataCode(), report));
        }
        return result;
    }

    /** Stored current observations only: never calls the source (used by the disruption monitor). */
    public Map<String, WeatherReport> storedCurrentObservations() {
        Map<String, WeatherReport> result = new LinkedHashMap<>();
        for (Airport airport : airportRepository.findAllByOrderByIataCodeAsc()) {
            usable(observation(airport, false)).ifPresent(report -> result.put(airport.getIataCode(), report));
        }
        return result;
    }

    /** Weather of one airport for the dashboard: latest observation and upcoming forecast steps. */
    public AirportWeatherDto airportWeather(Airport airport) {
        Instant now = clock.instant();
        Optional<WeatherReport> observation = observation(airport, true);
        List<WeatherReportDto> forecast = reportRepository
                .findByAirportIdAndKindAndValidAtAfterOrderByValidAtAsc(
                        airport.getAirportId(), WeatherReport.Kind.FORECAST, now)
                .stream().limit(forecastSteps).map(WeatherReportDto::of).toList();

        if (observation.isEmpty()) {
            String note = !client.configured()
                    ? "No weather source is configured (set WEATHER_API_KEY)."
                    : lastError != null ? "The weather source could not be read: " + lastError
                    : "No observation has been received yet.";
            return new AirportWeatherDto(airport.getIataCode(), AirportWeatherDto.UNAVAILABLE, client.configured(),
                    null, null, forecast, note);
        }
        WeatherReport report = observation.get();
        long ageMinutes = Math.max(0, Duration.between(report.getValidAt(), now).toMinutes());
        boolean current = usable(observation).isPresent();
        return new AirportWeatherDto(airport.getIataCode(),
                current ? AirportWeatherDto.LIVE : AirportWeatherDto.STALE, client.configured(),
                WeatherReportDto.of(report), ageMinutes, forecast,
                current ? null : "The last observation is " + ageMinutes
                        + " minutes old and is not used for predictions or detection.");
    }

    /**
     * Refreshes observations (and forecasts) that are due, for every airport.
     * Called by the scheduler; safe to call at any time.
     *
     * @return number of airports whose observation was refreshed
     */
    public int refreshDue() {
        if (!client.configured()) {
            return 0;
        }
        int refreshed = 0;
        Instant now = clock.instant();
        for (Airport airport : airportRepository.findAllByOrderByIataCodeAsc()) {
            if (now.isBefore(pausedUntil)) {
                break;
            }
            Optional<WeatherReport> stored = latestObservation(airport);
            if (stored.isEmpty() || isDue(stored.get(), now)) {
                if (fetchObservation(airport).isPresent()) {
                    refreshed++;
                }
            }
            if (forecastEnabled && !clock.instant().isBefore(pausedUntil)) {
                refreshForecast(airport);
            }
        }
        reportRepositoryPrune(now);
        return refreshed;
    }

    /** Freshness of the weather feed for the status endpoint. */
    public FeedStatus status() {
        Instant success = lastSuccessAt != null ? lastSuccessAt
                : reportRepository.findFirstByKindOrderByFetchedAtDesc(WeatherReport.Kind.OBSERVATION)
                        .map(WeatherReport::getFetchedAt).orElse(null);
        return new FeedStatus(client.configured(), success, lastAttemptAt, lastError, refreshInterval);
    }

    /**
     * @param configured    whether an API key is set
     * @param lastSuccessAt when an observation was last fetched successfully
     * @param lastError     why the last call failed; null after a success
     */
    public record FeedStatus(boolean configured, Instant lastSuccessAt, Instant lastAttemptAt, String lastError,
                             Duration refreshInterval) {
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * The latest observation of an airport: the stored one, replaced by a
     * fresh fetch when {@code refresh} is set and it is due.
     */
    private Optional<WeatherReport> observation(Airport airport, boolean refresh) {
        Optional<WeatherReport> stored = latestObservation(airport);
        if (!refresh || !client.configured()) {
            return stored;
        }
        Instant now = clock.instant();
        if (stored.isPresent() && !isDue(stored.get(), now)) {
            return stored;
        }
        if (now.isBefore(pausedUntil)) {
            return stored;
        }
        Optional<WeatherReport> fetched = fetchObservation(airport);
        return fetched.isPresent() ? fetched : stored;
    }

    private Optional<WeatherReport> latestObservation(Airport airport) {
        return reportRepository.findFirstByAirportIdAndKindOrderByValidAtDesc(
                airport.getAirportId(), WeatherReport.Kind.OBSERVATION);
    }

    private boolean isDue(WeatherReport report, Instant now) {
        return !now.isBefore(report.getFetchedAt().plus(refreshInterval));
    }

    /** Only an observation that is recent enough counts as the current weather. */
    private Optional<WeatherReport> usable(Optional<WeatherReport> report) {
        Instant oldest = clock.instant().minus(maxObservationAge);
        return report.filter(r -> !r.getValidAt().isBefore(oldest));
    }

    private Optional<WeatherReport> fetchObservation(Airport airport) {
        Instant now = clock.instant();
        lastAttemptAt = now;
        try {
            WeatherReading reading = client.fetchCurrent(airport.getLatitude(), airport.getLongitude());
            WeatherReport report = save(airport, reading, now);
            lastSuccessAt = now;
            lastError = null;
            log.info("event=weather_observation airport={} score={} observed_at={} source={}",
                    airport.getIataCode(), report.getSeverityScore(), report.getValidAt(),
                    DataSources.OPENWEATHERMAP);
            return Optional.of(report);
        } catch (WeatherUnavailableException ex) {
            sourceFailed(now, ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            sourceFailed(now, "could not store the observation (" + ex.getClass().getSimpleName() + ")");
            return Optional.empty();
        }
    }

    private void refreshForecast(Airport airport) {
        Instant now = clock.instant();
        Instant fetched = forecastFetchedAt.get(airport.getIataCode());
        if (fetched != null && now.isBefore(fetched.plus(forecastRefreshInterval))) {
            return;
        }
        try {
            for (WeatherReading reading
                    : client.fetchForecast(airport.getLatitude(), airport.getLongitude(), forecastSteps)) {
                save(airport, reading, now);
            }
            forecastFetchedAt.put(airport.getIataCode(), now);
        } catch (WeatherUnavailableException ex) {
            sourceFailed(now, ex.getMessage());
        } catch (RuntimeException ex) {
            sourceFailed(now, "could not store the forecast (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private void sourceFailed(Instant now, String reason) {
        lastError = reason;
        pausedUntil = now.plus(FAILURE_PAUSE);
        log.warn("event=weather_unavailable source={} retry_after_s={} reason=\"{}\"",
                DataSources.OPENWEATHERMAP, FAILURE_PAUSE.getSeconds(), reason);
    }

    /** Inserts the report, or refreshes the stored one with the same airport, kind and time. */
    private WeatherReport save(Airport airport, WeatherReading reading, Instant fetchedAt) {
        WeatherReport report = reportRepository.findByAirportIdAndKindAndValidAtAndDataSource(
                        airport.getAirportId(), reading.kind(), reading.validAt(), DataSources.OPENWEATHERMAP)
                .orElseGet(() -> WeatherReport.builder()
                        .airportId(airport.getAirportId())
                        .kind(reading.kind())
                        .validAt(reading.validAt())
                        .dataSource(DataSources.OPENWEATHERMAP)
                        .build());
        report.setFetchedAt(fetchedAt);
        report.setSeverityScore(reading.severityScore());
        report.setConditionCode(reading.conditionCode());
        report.setDescription(reading.description());
        report.setWindSpeedMs(reading.windSpeedMs());
        report.setVisibilityM(reading.visibilityM());
        report.setTemperatureC(reading.temperatureC());
        report.setPrecipitationMm(reading.precipitationMm());
        report.setPrecipitationProbability(reading.precipitationProbability());
        return reportRepository.save(report);
    }

    private void reportRepositoryPrune(Instant now) {
        try {
            reportRepository.deleteOlderThan(now.minus(RETENTION));
        } catch (RuntimeException ex) {
            log.warn("event=weather_prune_failed error={}", ex.getClass().getSimpleName());
        }
    }
}
