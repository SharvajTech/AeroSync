package com.aerosync.dto.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Live aircraft at or near one airport.
 *
 * @param status          LIVE: the feed delivered recently. STALE: the last
 *                        successful poll is older than expected. DISABLED:
 *                        live ingestion is off (data mode SYNTHETIC or switched
 *                        off). NO_DATA: enabled, but nothing has been received yet.
 * @param radiusKm        aircraft within this distance of the airport are listed
 * @param lastSuccessAt   last successful poll of the live feed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirportLiveTrafficDto(
        String airport,
        String status,
        String dataSource,
        double radiusKm,
        Instant lastSuccessAt,
        Instant generatedAt,
        int onGround,
        int airborne,
        List<LiveAircraftDto> aircraft,
        String note) {

    public static final String LIVE = "LIVE";
    public static final String STALE = "STALE";
    public static final String DISABLED = "DISABLED";
    public static final String NO_DATA = "NO_DATA";
}
