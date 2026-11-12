package com.aerosync.ingestion;

import com.aerosync.ingestion.AirportDirectory.AirportRef;
import com.aerosync.model.Airport;
import com.aerosync.repository.AirportRepository;
import com.aerosync.synthetic.ReferenceAirports;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link AirportDirectory} from the {@code airports} table and
 * keeps it for a few minutes (airports change rarely; ingestion runs often).
 */
@Component
public class AirportDirectoryProvider {

    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final AirportRepository airportRepository;
    private final Clock clock;

    private volatile AirportDirectory cached;
    private volatile Instant loadedAt = Instant.EPOCH;

    public AirportDirectoryProvider(AirportRepository airportRepository, Clock clock) {
        this.airportRepository = airportRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AirportDirectory get() {
        AirportDirectory current = cached;
        if (current != null && clock.instant().isBefore(loadedAt.plus(MAX_AGE))) {
            return current;
        }
        List<AirportRef> refs = new ArrayList<>();
        for (Airport airport : airportRepository.findAllByOrderByIataCodeAsc()) {
            // Airports created before migration V4 (or in tests) may lack the ICAO
            // code; the reference list supplies it for the airports it knows.
            String icao = airport.getIcaoCode() != null
                    ? airport.getIcaoCode()
                    : ReferenceAirports.icaoCode(airport.getIataCode());
            refs.add(new AirportRef(airport.getAirportId(), airport.getIataCode(), icao,
                    ZoneId.of(airport.getTimezone()), airport.getLatitude(), airport.getLongitude()));
        }
        AirportDirectory directory = new AirportDirectory(refs);
        cached = directory;
        loadedAt = clock.instant();
        return directory;
    }

    /** Forget the cached directory (tests, or after airports were changed). */
    public void refresh() {
        cached = null;
        loadedAt = Instant.EPOCH;
    }
}
