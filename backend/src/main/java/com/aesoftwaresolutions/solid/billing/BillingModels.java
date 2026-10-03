package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shapes for the billing module. */
public final class BillingModels {

    private BillingModels() {
    }

    public record Customer(UUID id, UUID entityId, String name, String email, String phone, String billingAddress,
                           String notes, boolean isArchived, OffsetDateTime createdAt) {
    }

    public record InvoiceLine(UUID id, int lineNo, String description, BigDecimal quantity, Money unitPrice,
                              Money amount, UUID incomeAccountId, UUID taxRateId, Money taxAmount) {
    }

    /**
     * @param total   what the customer owes: the lines plus any sales tax
     * @param taxTotal sales tax charged — money held for the state, never income
     * @param creditsApplied credit notes pointed at this invoice; money that was never owed rather than paid
     * @param businessLineId the facet of the business this sale belongs to (spec 069); null = unassigned
     */
    public record Invoice(UUID id, UUID entityId, UUID customerId, String invoiceNumber, LocalDate issueDate,
                          LocalDate dueDate, String terms, String memo, Money total, Money amountPaid,
                          Money balanceDue, String status, UUID journalEntryId, List<InvoiceLine> lines,
                          Money taxTotal, Money creditsApplied, UUID businessLineId) {
    }

    /**
     * @param invoiceLineId the charge this line is taking back, when there is one — where its tax comes from
     * @param taxAmount     the sales tax reversed with it (spec 058)
     */
    public record CreditNoteLine(UUID id, int lineNo, String description, BigDecimal quantity, Money unitPrice,
                                 Money amount, UUID incomeAccountId, UUID invoiceLineId, UUID taxRateId,
                                 Money taxAmount) {
    }

    public record CreditApplication(UUID id, UUID invoiceId, String invoiceNumber, Money amount) {
    }

    /**
     * @param applied   how much of this credit has been pointed at invoices
     * @param remaining what is left to point somewhere
     */
    public record CreditNote(UUID id, UUID customerId, String customerName, String creditNumber, LocalDate issueDate,
                             String memo, Money total, String status, UUID journalEntryId, Money applied,
                             Money remaining, List<CreditNoteLine> lines, List<CreditApplication> applications,
                             Money taxTotal) {
    }

    public record PaymentApplication(UUID invoiceId, Money amount) {
    }

    public record Payment(UUID id, UUID entityId, UUID customerId, LocalDate receivedDate, Money amount,
                          UUID depositAccountId, String method, String reference, UUID journalEntryId,
                          List<PaymentApplication> applications) {
    }

    public record AgingBucket(UUID customerId, String customerName, Money current, Money days1to30, Money days31to60,
                              Money days61to90, Money days90plus, Money total) {
    }

    public record AgingReport(LocalDate asOf, String currency, List<AgingBucket> customers, AgingBucket totals) {
    }
}
