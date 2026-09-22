package com.aesoftwaresolutions.solid.iam;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Reads a {@code timestamptz} out of a generic row map without assuming what the driver returned.
 *
 * <p>pgjdbc hands back a {@link java.sql.Timestamp} today, but a cast that is only true by accident is how
 * both recovery paths — accepting an invitation and spending a reset link — would break at once if it ever
 * changed (spec 050).
 */
final class Timestamps {

    private Timestamps() {
    }

    static Instant toInstant(Object value) {
        return switch (value) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case Instant instant -> instant;
            case null -> throw new IllegalStateException("Expected a timestamp, found nothing");
            default -> throw new IllegalStateException(
                    "Expected a timestamp, found " + value.getClass().getName());
        };
    }
}
