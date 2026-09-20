package com.aesoftwaresolutions.solid.salestax;

import com.aesoftwaresolutions.solid.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes for sales tax. */
public final class SalesTaxModels {

    private SalesTaxModels() {
    }

    public record Rate(UUID id, UUID entityId, String jurisdiction, BigDecimal ratePercent, UUID liabilityAccountId,
                       LocalDate effectiveFrom, LocalDate effectiveTo, String note, boolean active) {
    }

    public record JurisdictionTotal(String jurisdiction, BigDecimal ratePercent, Money taxableSales,
                                    Money taxCollected) {
    }

    public record SalesTaxReport(LocalDate from, LocalDate to, String currency, List<JurisdictionTotal> jurisdictions,
                                 Money totalTaxable, Money totalCollected, String note) {
    }
}
