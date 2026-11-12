package com.aerosync.ingestion;

import com.aerosync.datasource.DataSources;
import com.aerosync.model.LiveAircraftState;
import com.aerosync.repository.LiveAircraftStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes validated live aircraft states. Idempotent: there is one row per
 * aircraft, a state is written only when it is newer than the stored one, and
 * storing the same snapshot twice changes nothing.
 */
@Service
public class LiveStateStore {

    private final LiveAircraftStateRepository repository;

    public LiveStateStore(LiveAircraftStateRepository repository) {
        this.repository = repository;
    }

    /**
     * @param states validated states of one snapshot
     * @param now    ingestion time recorded on written rows
     * @param counts receives inserted / updated / unchanged
     */
    @Transactional
    public void store(List<NormalizedAircraftState> states, Instant now, IngestionCounts counts) {
        // One state per aircraft: if a snapshot lists an address twice, keep the newest.
        Map<String, NormalizedAircraftState> latest = new LinkedHashMap<>();
        for (NormalizedAircraftState state : states) {
            latest.merge(state.icao24(), state,
                    (a, b) -> b.lastContact().isAfter(a.lastContact()) ? b : a);
        }
        int duplicates = states.size() - latest.size();
        for (int i = 0; i < duplicates; i++) {
            counts.addUnchanged();
        }

        Map<String, LiveAircraftState> existing = new HashMap<>();
        for (LiveAircraftState row : repository.findAllById(latest.keySet())) {
            existing.put(row.getIcao24(), row);
        }
        List<LiveAircraftState> toSave = new ArrayList<>();
        for (NormalizedAircraftState state : latest.values()) {
            LiveAircraftState row = existing.get(state.icao24());
            if (row == null) {
                row = new LiveAircraftState();
                row.setIcao24(state.icao24());
                apply(row, state, now);
                toSave.add(row);
                counts.addInserted();
            } else if (state.lastContact().isAfter(row.getLastContact())) {
                apply(row, state, now);
                toSave.add(row);
                counts.addUpdated();
            } else {
                counts.addUnchanged();
            }
        }
        repository.saveAll(toSave);
    }

    /** Deletes states not heard from since {@code before}. */
    @Transactional
    public int prune(Instant before) {
        return repository.deleteOlderThan(before);
    }

    private static void apply(LiveAircraftState row, NormalizedAircraftState state, Instant now) {
        row.setCallsign(state.callsign());
        row.setFlightNumber(state.flightNumber());
        row.setOperatingAirline(state.operatingAirline());
        row.setOriginCountry(state.originCountry());
        row.setLatitude(state.latitude());
        row.setLongitude(state.longitude());
        row.setBaroAltitudeM(state.baroAltitudeM());
        row.setGeoAltitudeM(state.geoAltitudeM());
        row.setOnGround(state.onGround());
        row.setVelocityMs(state.velocityMs());
        row.setTrueTrackDeg(state.trueTrackDeg());
        row.setVerticalRateMs(state.verticalRateMs());
        row.setSquawk(state.squawk());
        row.setPositionSource(state.positionSource());
        row.setTimePosition(state.timePosition());
        row.setLastContact(state.lastContact());
        row.setNearestAirportId(state.nearestAirportId());
        row.setNearestAirportDistanceKm(state.nearestAirportDistanceKm());
        row.setDataSource(DataSources.OPENSKY);
        row.setSnapshotTime(state.snapshotTime());
        row.setIngestedAt(now);
    }
}
