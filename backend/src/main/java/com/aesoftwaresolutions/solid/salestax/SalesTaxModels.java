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

    /**
     * @param taxableSales  net of credits, as it has always been
     * @param taxCollected  net of credits, as it has always been
     * @param taxCharged    what invoices charged in the period, before any credit
     * @param taxCredited   what credit notes issued in the period took back out (spec 059)
     */
    public record JurisdictionTotal(String jurisdiction, BigDecimal ratePercent, Money taxableSales,
                                    Money taxCollected, Money taxableCharged, Money taxCharged,
                                    Money taxableCredited, Money taxCredited) {
    }

    /**
     * A credit issued in this period against an invoice from an earlier one.
     *
     * <p>Solid counts it here, in the period the credit was issued, and never reopens the old period. Where a
     * state instead wants the original period's return amended, this is the list of returns to amend — see
     * docs/tax-sources/credit-note-sales-tax.md.
     */
    public record PriorPeriodAdjustment(String creditNumber, LocalDate creditDate, String invoiceNumber,
                                        LocalDate invoiceDate, String jurisdiction, Money taxableReversed,
                                        Money taxReversed) {
    }

    public record SalesTaxReport(LocalDate from, LocalDate to, String currency, List<JurisdictionTotal> jurisdictions,
                                 Money totalTaxable, Money totalCollected,
                                 List<PriorPeriodAdjustment> priorPeriodAdjustments, String note) {
    }
}
