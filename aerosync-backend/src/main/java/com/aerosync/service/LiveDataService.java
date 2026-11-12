package com.aerosync.service;

import com.aerosync.datasource.DataSources;
import com.aerosync.dto.live.AirportLiveTrafficDto;
import com.aerosync.dto.live.AirportWeatherDto;
import com.aerosync.dto.live.LiveAircraftDto;
import com.aerosync.exception.InvalidRequestParameterException;
import com.aerosync.exception.ResourceNotFoundException;
import com.aerosync.graph.NetworkIdentifiers;
import com.aerosync.ingestion.FeedState;
import com.aerosync.ingestion.IngestionSettings;
import com.aerosync.ingestion.OpenSkyIngestionJob;
import com.aerosync.model.Airport;
import com.aerosync.model.LiveAircraftState;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.LiveAircraftStateRepository;
import com.aerosync.security.AirportScope;
import com.aerosync.status.SystemStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Live data of one airport for the dashboard: aircraft observed at or near it
 * and its weather.
 *
 * <p>Airport-scoped like every other read: 404 for an unknown airport, 403
 * unless the airport is one of the caller's. A live aircraft belongs to the
 * airport it is nearest to (within the configured radius), so a user sees the
 * traffic around their own airports and nothing else; aircraft en route far
 * from every airport are not served at all.</p>
 */
@Service
public class LiveDataService {

    /** Aircraft not heard from for this long are no longer listed. */
    static final Duration LISTING_WINDOW = Duration.ofMinutes(30);

    private final AirportRepository airportRepository;
    private final LiveAircraftStateRepository stateRepository;
    private final OpenSkyIngestionJob openSky;
    private final IngestionSettings ingestionSettings;
    private final WeatherService weatherService;
    private final Clock clock;

    public LiveDataService(AirportRepository airportRepository, LiveAircraftStateRepository stateRepository,
                           OpenSkyIngestionJob openSky, IngestionSettings ingestionSettings,
                           WeatherService weatherService, Clock clock) {
        this.airportRepository = airportRepository;
        this.stateRepository = stateRepository;
        this.openSky = openSky;
        this.ingestionSettings = ingestionSettings;
        this.weatherService = weatherService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AirportLiveTrafficDto liveTraffic(String iataCode, AirportScope scope) {
        Airport airport = requireAirport(iataCode, scope);
        Instant now = clock.instant();
        double radius = ingestionSettings.vicinityKm();
        if (!openSky.active()) {
            // Not served in this mode even if rows from an earlier LIVE/HYBRID run exist.
            return new AirportLiveTrafficDto(airport.getIataCode(), AirportLiveTrafficDto.DISABLED,
                    DataSources.OPENSKY, radius, null, now, 0, 0, List.of(),
                    "Live aircraft data is not fetched in the current configuration.");
        }
        FeedState feed = openSky.statesFeed();
        Duration interval = feed.effectiveInterval() != null
                ? feed.effectiveInterval() : ingestionSettings.statesPollInterval();
        // An aircraft is stale when it was not heard from for two polls.
        Duration staleAfter = interval.multipliedBy(2).plusSeconds(60);
        List<LiveAircraftDto> aircraft = stateRepository
                .findByNearestAirportIdAndLastContactAfterOrderByLastContactDesc(
                        airport.getAirportId(), now.minus(LISTING_WINDOW))
                .stream()
                .filter(state -> DataSources.OPENSKY.equals(state.getDataSource()))
                .map(state -> LiveAircraftDto.of(state, now, staleAfter))
                .toList();
        int onGround = (int) aircraft.stream().filter(LiveAircraftDto::onGround).count();

        Instant lastSuccess = feed.lastSuccessAt();
        String status;
        String note = null;
        if (lastSuccess == null) {
            status = AirportLiveTrafficDto.NO_DATA;
            note = feed.lastError() != null
                    ? "The live feed has not delivered since the backend started: " + feed.lastError()
                    : "Waiting for the first poll of the live feed.";
        } else if (SystemStatusService.isStale(lastSuccess, interval, now) || feed.consecutiveFailures() > 0) {
            status = AirportLiveTrafficDto.STALE;
            note = "The live feed last delivered at " + lastSuccess + "; positions may be out of date.";
        } else {
            status = AirportLiveTrafficDto.LIVE;
        }
        return new AirportLiveTrafficDto(airport.getIataCode(), status, DataSources.OPENSKY, radius, lastSuccess,
                now, onGround, aircraft.size() - onGround, aircraft, note);
    }

    public AirportWeatherDto weather(String iataCode, AirportScope scope) {
        return weatherService.airportWeather(requireAirport(iataCode, scope));
    }

    /** 400 for a malformed code, 404 for an unknown airport, 403 unless it is in scope (same order as the graph). */
    private Airport requireAirport(String iataCode, AirportScope scope) {
        String iata;
        try {
            iata = NetworkIdentifiers.normalizeIata(iataCode);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestParameterException(ex.getMessage());
        }
        Airport airport = airportRepository.findByIataCode(iata)
                .orElseThrow(() -> new ResourceNotFoundException("Airport " + iata + " not found"));
        scope.requireAirport(iata);
        return airport;
    }
}
