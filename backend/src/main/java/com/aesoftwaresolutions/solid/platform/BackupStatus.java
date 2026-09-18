package com.aesoftwaresolutions.solid.platform;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * What an operator needs to sanity-check a backup or a restore: which installation this is, which master key it
 * expects, how big the data is, and roughly how many rows each important table holds.
 *
 * @param tableEstimates PostgreSQL's own row estimates — close, not exact, and plenty to spot a half-restored database
 */
public record BackupStatus(UUID instanceId, String keyFingerprint, String schemaVersion, String appVersion,
                           long databaseBytes, Map<String, Long> tableEstimates, Documents documents,
                           String documentsRoot, OffsetDateTime checkedAt) {

    public record Documents(long count, long bytes) {
    }
}
