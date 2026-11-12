package com.aerosync.ingestion;

import com.aerosync.datasource.DataSources;
import com.aerosync.model.DataOrigin;
import com.aerosync.model.FlightInstance;
import com.aerosync.model.FlightInstanceStatus;
import com.aerosync.model.FlightSchedule;
import com.aerosync.model.ScheduleBasis;
import com.aerosync.repository.AirportRepository;
import com.aerosync.repository.FlightInstanceRepository;
import com.aerosync.repository.FlightScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes validated external flight legs into the existing operational tables
 * ({@code flight_schedules} + {@code flight_instances}).
 *
 * <ul>
 *   <li><b>Labelled:</b> rows are {@code data_origin = IMPORTED},
 *       {@code data_source = OPENSKY}, {@code schedule_basis = OBSERVED} (the
 *       times are observations, not a timetable, so no delay is recorded).</li>
 *   <li><b>Idempotent:</b> a record is found again by its source record id;
 *       importing it twice leaves it unchanged, and a changed record is
 *       updated in place.</li>
 *   <li><b>No duplicates across sources:</b> a leg is the key
 *       (flight number, local date, origin). If that leg already exists from
 *       another source (for example the synthetic network in HYBRID mode) the
 *       external record is skipped; a synthetic flight is never relabelled or
 *       overwritten.</li>
 * </ul>
 */
@Service
public class FlightLegStore {

    private final FlightScheduleRepository scheduleRepository;
    private final FlightInstanceRepository instanceRepository;
    private final AirportRepository airportRepository;

    public FlightLegStore(FlightScheduleRepository scheduleRepository,
                          FlightInstanceRepository instanceRepository,
                          AirportRepository airportRepository) {
        this.scheduleRepository = scheduleRepository;
        this.instanceRepository = instanceRepository;
        this.airportRepository = airportRepository;
    }

    /** Stores one batch in one transaction: all of it or none of it. */
    @Transactional
    public void store(List<NormalizedFlightLeg> legs, Instant now, IngestionCounts counts) {
        Set<String> seenRecords = new HashSet<>();
        Set<String> seenLegs = new HashSet<>();
        for (NormalizedFlightLeg leg : legs) {
            String legKey = leg.flightNumber() + "|" + leg.serviceDate() + "|" + leg.origin().airportId();
            if (!seenRecords.add(leg.sourceRecordId())) {
                counts.addUnchanged();
                continue;
            }
            Optional<FlightSchedule> bySource =
                    scheduleRepository.findByDataSourceAndSourceRecordId(DataSources.OPENSKY, leg.sourceRecordId());
            if (bySource.isPresent()) {
                seenLegs.add(legKey);
                if (refresh(bySource.get(), leg, now)) {
                    counts.addUpdated();
                } else {
                    counts.addUnchanged();
                }
                continue;
            }
            if (!seenLegs.add(legKey)) {
                counts.addSkipped(RejectReason.DUPLICATE_LEG);
                continue;
            }
            Optional<FlightSchedule> byLeg = scheduleRepository.findByFlightNumberAndServiceDateAndOrigin_AirportId(
                    leg.flightNumber(), leg.serviceDate(), leg.origin().airportId());
            if (byLeg.isPresent()) {
                counts.addSkipped(DataSources.OPENSKY.equals(byLeg.get().getDataSource())
                        ? RejectReason.DUPLICATE_LEG
                        : RejectReason.LEG_EXISTS_FROM_OTHER_SOURCE);
                continue;
            }
            insert(leg, now);
            counts.addInserted();
        }
    }

    private void insert(NormalizedFlightLeg leg, Instant now) {
        FlightSchedule schedule = scheduleRepository.save(FlightSchedule.builder()
                .flightNumber(leg.flightNumber())
                .origin(airportRepository.getReferenceById(leg.origin().airportId()))
                .destination(airportRepository.getReferenceById(leg.destination().airportId()))
                // OBSERVED basis: these are the observed departure and arrival.
                .scheduledDeparture(leg.departure())
                .scheduledArrival(leg.arrival())
                .operatingAirline(leg.operatingAirline())
                .aircraftType(null)                 // OpenSky does not report the type
                .serviceDate(leg.serviceDate())
                .dataOrigin(DataOrigin.IMPORTED)
                .dataSource(DataSources.OPENSKY)
                .scheduleBasis(ScheduleBasis.OBSERVED)
                .sourceRecordId(leg.sourceRecordId())
                .callsign(leg.callsign())
                .aircraftIcao24(leg.icao24())
                .ingestedAt(now)
                .sourceUpdatedAt(now)
                .build());
        instanceRepository.save(FlightInstance.builder()
                .schedule(schedule)
                .actualDeparture(leg.departure())
                .actualArrival(leg.arrival())
                .status(FlightInstanceStatus.ARRIVED)
                .delayMinutes(0)                    // unknown: there is no timetable to compare with
                .build());
    }

    /** @return true when the stored leg differed from the source record and was updated */
    private boolean refresh(FlightSchedule schedule, NormalizedFlightLeg leg, Instant now) {
        boolean sameAirports = schedule.getOrigin().getAirportId().equals(leg.origin().airportId())
                && schedule.getDestination().getAirportId().equals(leg.destination().airportId());
        boolean same = sameAirports
                && Objects.equals(schedule.getScheduledDeparture(), leg.departure())
                && Objects.equals(schedule.getScheduledArrival(), leg.arrival())
                && Objects.equals(schedule.getCallsign(), leg.callsign());
        if (same) {
            return false;
        }
        if (!schedule.getOrigin().getAirportId().equals(leg.origin().airportId())) {
            // The leg key (origin) changed: leave the stored row alone rather than move it.
            return false;
        }
        schedule.setDestination(airportRepository.getReferenceById(leg.destination().airportId()));
        schedule.setScheduledDeparture(leg.departure());
        schedule.setScheduledArrival(leg.arrival());
        schedule.setCallsign(leg.callsign());
        schedule.setSourceUpdatedAt(now);
        scheduleRepository.save(schedule);
        instanceRepository.findBySchedule_ScheduleId(schedule.getScheduleId()).ifPresent(instance -> {
            instance.setActualDeparture(leg.departure());
            instance.setActualArrival(leg.arrival());
            instanceRepository.save(instance);
        });
        return true;
    }
}
