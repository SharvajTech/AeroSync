package com.aerosync.controller;

import com.aerosync.dto.live.AirportLiveTrafficDto;
import com.aerosync.dto.live.AirportWeatherDto;
import com.aerosync.dto.status.SystemStatusDto;
import com.aerosync.security.AuthenticatedUser;
import com.aerosync.service.LiveDataService;
import com.aerosync.status.SystemStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 4 read endpoints: live aircraft and weather of an airport, and the
 * status of the data pipeline. All need a token; the airport endpoints are
 * limited to the caller's airports (403 otherwise).
 */
@RestController
public class LiveDataController {

    private final LiveDataService liveDataService;
    private final SystemStatusService systemStatusService;

    public LiveDataController(LiveDataService liveDataService, SystemStatusService systemStatusService) {
        this.liveDataService = liveDataService;
        this.systemStatusService = systemStatusService;
    }

    /** Aircraft observed at or near the airport (OpenSky live states). */
    @GetMapping("/api/airports/{iataCode}/live-aircraft")
    public ResponseEntity<AirportLiveTrafficDto> liveAircraft(AuthenticatedUser user,
                                                              @PathVariable String iataCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(liveDataService.liveTraffic(iataCode, user.scope()));
    }

    /** Latest weather observation and upcoming forecast steps of the airport. */
    @GetMapping("/api/airports/{iataCode}/weather")
    public ResponseEntity<AirportWeatherDto> weather(AuthenticatedUser user, @PathVariable String iataCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(liveDataService.weather(iataCode, user.scope()));
    }

    /**
     * Data mode, feed freshness, dependency health and monitor status. The
     * same for every signed-in user: it describes the pipeline and contains
     * no flight, airport or disruption data.
     */
    @GetMapping("/api/system/status")
    public ResponseEntity<SystemStatusDto> status(AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(systemStatusService.status());
    }
}
