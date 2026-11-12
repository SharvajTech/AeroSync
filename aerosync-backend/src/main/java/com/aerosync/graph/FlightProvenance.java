package com.aerosync.graph;

import com.aerosync.model.ScheduleBasis;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Where a flight record came from and how fresh it is.
 *
 * @param dataSource      system that wrote the record (SYNTHETIC_GENERATOR, OPENSKY, ...)
 * @param scheduleBasis   PLANNED (a timetable) or OBSERVED (no timetable known;
 *                        the scheduled times are the observed departure and arrival)
 * @param sourceRecordId  record id in the external source, if any
 * @param callsign        callsign reported by the external source, if any
 * @param aircraftIcao24  transponder address reported by the external source, if any
 * @param ingestedAt      when ingestion first wrote the record, if it did
 * @param sourceUpdatedAt when ingestion last confirmed or changed it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightProvenance(
        String dataSource,
        ScheduleBasis scheduleBasis,
        String sourceRecordId,
        String callsign,
        String aircraftIcao24,
        Instant ingestedAt,
        Instant sourceUpdatedAt) {
}
