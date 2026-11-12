package com.aerosync.ingestion;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/** What one ingestion step did with the records it was given. Mutable accumulator. */
public final class IngestionCounts {

    private int fetched;
    private int inserted;
    private int updated;
    private int unchanged;
    private final Map<RejectReason, Integer> skipped = new EnumMap<>(RejectReason.class);
    private int malformed;

    public void addFetched(int count) {
        fetched += count;
    }

    public void addInserted() {
        inserted++;
    }

    public void addUpdated() {
        updated++;
    }

    public void addUnchanged() {
        unchanged++;
    }

    public void addSkipped(RejectReason reason) {
        skipped.merge(reason, 1, Integer::sum);
    }

    /** Entries the source sent that were not a documented record at all. */
    public void addMalformed(int count) {
        malformed += count;
    }

    public void add(IngestionCounts other) {
        fetched += other.fetched;
        inserted += other.inserted;
        updated += other.updated;
        unchanged += other.unchanged;
        malformed += other.malformed;
        other.skipped.forEach((reason, count) -> skipped.merge(reason, count, Integer::sum));
    }

    public int fetched() {
        return fetched;
    }

    public int inserted() {
        return inserted;
    }

    public int updated() {
        return updated;
    }

    public int unchanged() {
        return unchanged;
    }

    public int skipped() {
        return malformed + skipped.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int skipped(RejectReason reason) {
        return skipped.getOrDefault(reason, 0);
    }

    /** {@code REASON=count} pairs for the run record; null when nothing was skipped. */
    public String skipDetails() {
        String reasons = skipped.entrySet().stream()
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        if (malformed > 0) {
            reasons = reasons.isEmpty() ? "MALFORMED=" + malformed : reasons + ", MALFORMED=" + malformed;
        }
        return reasons.isEmpty() ? null : reasons;
    }
}
