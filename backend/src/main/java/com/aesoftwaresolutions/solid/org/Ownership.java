package com.aesoftwaresolutions.solid.org;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Owner entity owns {@code percent}% of owned entity" during the effective period.
 * Percent is a string in JSON (e.g. "60.0000") to avoid floating-point rounding.
 */
public record Ownership(
        UUID id,
        UUID orgId,
        UUID ownerEntityId,
        UUID ownedEntityId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percent,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
