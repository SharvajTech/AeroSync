package com.aerosync.dto.graph;

import java.time.LocalDate;

/** First and last service dates that have flights in the database (null when empty). */
public record DataWindowDto(
        LocalDate firstServiceDate,
        LocalDate lastServiceDate) {
}
