package com.aerosync.datasource;

import com.aerosync.model.DataOrigin;

import java.util.Locale;

/**
 * Which records AeroSync produces and serves ({@code AEROSYNC_DATA_MODE}).
 *
 * <ul>
 *   <li>{@link #SYNTHETIC}: the generated demonstration data only; no external
 *       flight data is fetched. Works without any external API.</li>
 *   <li>{@link #LIVE}: externally sourced records only; nothing generated is
 *       seeded or served.</li>
 *   <li>{@link #HYBRID}: both, each record labelled with its source.</li>
 * </ul>
 *
 * Manually entered records are served in every mode.
 */
public enum DataSourceMode {
    SYNTHETIC,
    LIVE,
    HYBRID;

    /** Parses a configuration value; blank means {@link #SYNTHETIC}. */
    public static DataSourceMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SYNTHETIC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "AEROSYNC_DATA_MODE must be SYNTHETIC, LIVE or HYBRID, was '" + value.trim() + "'");
        }
    }

    /** Whether records of this origin are served in this mode. A missing origin counts as synthetic. */
    public boolean serves(DataOrigin origin) {
        DataOrigin effective = origin == null ? DataOrigin.SYNTHETIC : origin;
        return switch (effective) {
            case MANUAL -> true;
            case SYNTHETIC -> this != LIVE;
            case IMPORTED -> this != SYNTHETIC;
        };
    }

    /** Whether generated demonstration data is produced. */
    public boolean generatesSyntheticData() {
        return this != LIVE;
    }

    /** Whether external flight data is fetched. */
    public boolean ingestsExternalData() {
        return this != SYNTHETIC;
    }
}
