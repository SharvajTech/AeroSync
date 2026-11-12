package com.aerosync.ingestion;

import com.aerosync.ingestion.AirportDirectory.AirportRef;
import com.aerosync.ingestion.AirportDirectory.Nearest;
import com.aerosync.ingestion.CallsignMapper.FlightDesignator;
import com.aerosync.ingestion.opensky.OpenSkyFlight;
import com.aerosync.ingestion.opensky.OpenSkyStateVector;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates OpenSky records and maps them to AeroSync terms. Pure: no I/O.
 *
 * <p>Nothing is invented here. A value OpenSky did not report stays null, a
 * record that fails validation is rejected with a {@link RejectReason}, and
 * the only derived values are the flight number (from the callsign, see
 * {@link CallsignMapper}) and the nearest airport (from the position).</p>
 */
public final class OpenSkyNormalizer {

    /** OpenSky and the schema limit a leg to this block time. */
    static final Duration MAX_BLOCK_TIME = Duration.ofHours(20);
    /** Clock differences between OpenSky and this host are tolerated up to this. */
    static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(2);

    private static final Pattern ICAO24 = Pattern.compile("^[0-9a-f]{6}$");

    private final AirportDirectory airports;
    private final CallsignMapper callsigns;
    private final Duration maxStateAge;
    private final double vicinityKm;

    /**
     * @param maxStateAge a state whose last contact is older than this, measured
     *                    from the snapshot time, is not current and is rejected
     * @param vicinityKm  radius within which an aircraft is linked to an airport
     */
    public OpenSkyNormalizer(AirportDirectory airports, CallsignMapper callsigns,
                             Duration maxStateAge, double vicinityKm) {
        this.airports = airports;
        this.callsigns = callsigns;
        this.maxStateAge = maxStateAge;
        this.vicinityKm = vicinityKm;
    }

    public Normalization<NormalizedAircraftState> normalize(OpenSkyStateVector state, Instant snapshotTime,
                                                             Instant now) {
        if (state.icao24() == null || !ICAO24.matcher(state.icao24()).matches() || state.lastContact() == null) {
            return Normalization.rejected(RejectReason.INVALID_AIRCRAFT_ID);
        }
        if (state.latitude() == null || state.longitude() == null) {
            return Normalization.rejected(RejectReason.NO_POSITION);
        }
        double latitude = state.latitude();
        double longitude = state.longitude();
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return Normalization.rejected(RejectReason.INVALID_POSITION);
        }
        Instant lastContact = Instant.ofEpochSecond(state.lastContact());
        if (lastContact.isAfter(now.plus(CLOCK_TOLERANCE))) {
            return Normalization.rejected(RejectReason.FUTURE_TIMESTAMP);
        }
        if (lastContact.isBefore(snapshotTime.minus(maxStateAge))) {
            return Normalization.rejected(RejectReason.STALE);
        }

        Optional<FlightDesignator> flight = callsigns.toFlight(state.callsign());
        Optional<Nearest> nearest = airports.nearest(latitude, longitude, vicinityKm);
        return Normalization.accepted(new NormalizedAircraftState(
                state.icao24(),
                state.callsign(),
                flight.map(FlightDesignator::flightNumber).orElse(null),
                flight.map(FlightDesignator::airline).orElse(null),
                truncate(state.originCountry(), 80),
                latitude,
                longitude,
                state.baroAltitude(),
                state.geoAltitude(),
                state.onGround(),
                state.velocity(),
                state.trueTrack(),
                state.verticalRate(),
                state.squawk() != null && state.squawk().length() <= 4 ? state.squawk() : null,
                state.positionSource(),
                state.timePosition() == null ? null : Instant.ofEpochSecond(state.timePosition()),
                lastContact,
                nearest.map(n -> n.airport().airportId()).orElse(null),
                nearest.map(n -> n.airport().iata()).orElse(null),
                nearest.map(n -> Math.round(n.distanceKm() * 10.0) / 10.0).orElse(null),
                snapshotTime));
    }

    public Normalization<NormalizedFlightLeg> normalize(OpenSkyFlight flight, Instant now) {
        if (flight.icao24() == null || !ICAO24.matcher(flight.icao24()).matches()) {
            return Normalization.rejected(RejectReason.INVALID_AIRCRAFT_ID);
        }
        if (flight.firstSeen() == null || flight.lastSeen() == null) {
            return Normalization.rejected(RejectReason.INVALID_TIMES);
        }
        Instant departure = Instant.ofEpochSecond(flight.firstSeen());
        Instant arrival = Instant.ofEpochSecond(flight.lastSeen());
        if (!arrival.isAfter(departure) || Duration.between(departure, arrival).compareTo(MAX_BLOCK_TIME) > 0) {
            return Normalization.rejected(RejectReason.INVALID_TIMES);
        }
        if (arrival.isAfter(now.plus(CLOCK_TOLERANCE))) {
            return Normalization.rejected(RejectReason.FUTURE_TIMESTAMP);
        }
        if (flight.estDepartureAirport() == null || flight.estArrivalAirport() == null) {
            return Normalization.rejected(RejectReason.MISSING_AIRPORT);
        }
        Optional<AirportRef> origin = airports.byIcao(flight.estDepartureAirport());
        Optional<AirportRef> destination = airports.byIcao(flight.estArrivalAirport());
        if (origin.isEmpty() || destination.isEmpty()) {
            return Normalization.rejected(RejectReason.AIRPORT_NOT_IN_NETWORK);
        }
        if (origin.get().airportId().equals(destination.get().airportId())) {
            return Normalization.rejected(RejectReason.SAME_AIRPORT);
        }
        Optional<FlightDesignator> designator = callsigns.toFlight(flight.callsign());
        if (designator.isEmpty()) {
            return Normalization.rejected(RejectReason.UNMAPPED_CALLSIGN);
        }
        return Normalization.accepted(new NormalizedFlightLeg(
                flight.icao24() + ":" + flight.firstSeen(),
                designator.get().flightNumber(),
                designator.get().airline(),
                flight.callsign(),
                flight.icao24(),
                origin.get(),
                destination.get(),
                departure,
                arrival,
                departure.atZone(origin.get().timezone()).toLocalDate()));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
