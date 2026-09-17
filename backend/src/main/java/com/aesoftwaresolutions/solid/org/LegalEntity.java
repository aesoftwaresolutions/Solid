package com.aesoftwaresolutions.solid.org;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A person or business that keeps books and/or files tax returns. */
public record LegalEntity(
        UUID id,
        UUID orgId,
        String kind,
        String legalName,
        int fiscalYearEnd,
        String accountingMethod,
        String homeState,
        String baseCurrency,
        OffsetDateTime createdAt) {
}
