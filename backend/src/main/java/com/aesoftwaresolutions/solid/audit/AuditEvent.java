package com.aesoftwaresolutions.solid.audit;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        long seq,
        UUID id,
        OffsetDateTime occurredAt,
        UUID orgId,
        UUID actorUserId,
        String actorIp,
        String action,
        String objectType,
        UUID objectId,
        Map<String, Object> details) {
}
