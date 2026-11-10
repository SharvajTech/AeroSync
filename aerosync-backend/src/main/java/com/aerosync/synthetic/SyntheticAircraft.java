package com.aerosync.synthetic;

import java.util.UUID;

/** Synthetic tail. Registrations contain a digit (e.g. VT-A07), which no real Indian registration does. */
public record SyntheticAircraft(
        UUID aircraftId,
        String registration,
        String aircraftType,
        String operatingAirline,
        String initialAirportIata,
        int legCount) {
}
