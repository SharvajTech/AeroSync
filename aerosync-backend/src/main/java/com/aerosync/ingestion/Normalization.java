package com.aerosync.ingestion;

/**
 * Outcome of validating one external record: either the normalized value or
 * the reason it was rejected.
 */
public record Normalization<T>(T value, RejectReason rejected) {

    public static <T> Normalization<T> accepted(T value) {
        return new Normalization<>(value, null);
    }

    public static <T> Normalization<T> rejected(RejectReason reason) {
        return new Normalization<>(null, reason);
    }

    public boolean isAccepted() {
        return value != null;
    }
}
