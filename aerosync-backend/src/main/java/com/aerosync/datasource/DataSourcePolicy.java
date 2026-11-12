package com.aerosync.datasource;

import com.aerosync.model.DataOrigin;
import com.aerosync.model.Flight;
import com.aerosync.model.FlightInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * The configured {@link DataSourceMode} and the one rule every read path
 * applies: a record is served only when the mode serves its origin. Synthetic
 * records are therefore never returned in LIVE mode, and imported records are
 * never returned in SYNTHETIC mode.
 */
@Component
public class DataSourcePolicy {

    private final DataSourceMode mode;

    @Autowired
    public DataSourcePolicy(@Value("${aerosync.data.mode:SYNTHETIC}") String mode) {
        this(DataSourceMode.parse(mode));
    }

    public DataSourcePolicy(DataSourceMode mode) {
        this.mode = mode;
    }

    public DataSourceMode mode() {
        return mode;
    }

    public boolean serves(DataOrigin origin) {
        return mode.serves(origin);
    }

    /** Operational-network flight: the origin of its schedule. */
    public boolean serves(FlightInstance flight) {
        return serves(flight.getSchedule().getDataOrigin());
    }

    /** Flight of the original {@code flights} table; rows without an origin are synthetic. */
    public boolean serves(Flight flight) {
        return serves(flight.getDataOrigin());
    }

    /** The origins served in this mode, for repository queries. */
    public List<DataOrigin> servedOrigins() {
        return Arrays.stream(DataOrigin.values()).filter(mode::serves).toList();
    }
}
