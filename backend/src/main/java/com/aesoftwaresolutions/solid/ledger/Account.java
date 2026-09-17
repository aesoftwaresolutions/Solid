package com.aesoftwaresolutions.solid.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        UUID orgId,
        UUID entityId,
        String code,
        String name,
        AccountType type,
        String subtype,
        UUID parentId,
        boolean isHeader,
        String taxLineCode,
        boolean isArchived,
        OffsetDateTime createdAt) {
}
