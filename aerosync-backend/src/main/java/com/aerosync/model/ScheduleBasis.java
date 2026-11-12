package com.aerosync.model;

/**
 * What the {@code scheduled_*} times of a flight schedule mean.
 *
 * <p>{@link #PLANNED}: a timetable (synthetic generator, manual entry), so a
 * delay can be measured against it. {@link #OBSERVED}: the source reports no
 * timetable (OpenSky), the times are the observed departure and arrival, and
 * no delay is known.</p>
 */
public enum ScheduleBasis {
    PLANNED,
    OBSERVED
}
