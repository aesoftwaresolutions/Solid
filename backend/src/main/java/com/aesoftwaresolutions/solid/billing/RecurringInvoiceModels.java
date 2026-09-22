package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shapes for invoices that repeat (spec 052). */
public final class RecurringInvoiceModels {

    private RecurringInvoiceModels() {
    }

    public record Line(UUID id, int lineNo, String description, BigDecimal quantity, Money unitPrice,
                       Money amount, UUID incomeAccountId, UUID taxRateId) {
    }

    public record RecurringInvoice(UUID id, UUID entityId, UUID customerId, String customerName, String name,
                                   String memo, String terms, String frequency, LocalDate startDate,
                                   LocalDate endDate, int dayOfMonth, boolean active, List<Line> lines,
                                   Money total, LocalDate lastCreated) {
    }

    public record Created(UUID recurringInvoiceId, LocalDate date, UUID invoiceId, String invoiceNumber) {
    }

    /** @param reason why this occurrence was not created — an archived customer, a locked account, and so on */
    public record Skipped(UUID recurringInvoiceId, LocalDate date, String reason) {
    }

    public record RunResult(LocalDate through, List<Created> created, List<Skipped> skipped) {
    }
}
