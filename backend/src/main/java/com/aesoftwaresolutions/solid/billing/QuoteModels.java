package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shapes for quotes (spec 054). */
public final class QuoteModels {

    private QuoteModels() {
    }

    public record Line(UUID id, int lineNo, String description, BigDecimal quantity, Money unitPrice,
                       Money amount, UUID incomeAccountId) {
    }

    /**
     * @param status  draft, sent, accepted, declined, converted — or "expired", which is not stored but what
     *                the date says about a quote nobody has answered
     * @param expired true when the valid-until date has passed
     */
    public record Quote(UUID id, UUID customerId, String customerName, String quoteNumber, LocalDate issueDate,
                        LocalDate validUntil, String memo, Money total, String status, UUID invoiceId,
                        String declinedReason, boolean expired, List<Line> lines) {
    }
}
