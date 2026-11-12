package com.aerosync.ingestion.opensky;

import java.util.List;

/**
 * Parsed answer of {@code GET /states/all}.
 *
 * @param time      Unix time the snapshot is valid for
 * @param states    well-formed state vectors
 * @param malformed entries that were not a documented state vector and were dropped
 */
public record OpenSkyStates(long time, List<OpenSkyStateVector> states, int malformed) {
}
