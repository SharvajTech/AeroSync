package com.aerosync.datasource;

import com.aerosync.model.DataOrigin;

/**
 * Names of the systems that write records ({@code data_source} columns and
 * API fields). {@link DataOrigin} says whether a record is generated, external
 * or manual; the data source says exactly which system it came from.
 */
public final class DataSources {

    /** AeroSync's own generators: demonstration data, never real operations. */
    public static final String SYNTHETIC_GENERATOR = "SYNTHETIC_GENERATOR";
    /** Entered by a person through the API. */
    public static final String MANUAL_ENTRY = "MANUAL_ENTRY";
    /** An external import whose system was not recorded (rows older than Stage 4). */
    public static final String EXTERNAL_IMPORT = "EXTERNAL_IMPORT";
    /** OpenSky Network REST API. */
    public static final String OPENSKY = "OPENSKY";
    /** OpenWeatherMap API. */
    public static final String OPENWEATHERMAP = "OPENWEATHERMAP";

    private DataSources() {
    }

    /** Source name for records that only carry a {@link DataOrigin}. */
    public static String defaultFor(DataOrigin origin) {
        if (origin == null) {
            return SYNTHETIC_GENERATOR;
        }
        return switch (origin) {
            case SYNTHETIC -> SYNTHETIC_GENERATOR;
            case MANUAL -> MANUAL_ENTRY;
            case IMPORTED -> EXTERNAL_IMPORT;
        };
    }
}
