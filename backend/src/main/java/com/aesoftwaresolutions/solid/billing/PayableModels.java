package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shapes for vendors, bills and 1099 reporting. */
public final class PayableModels {

    private PayableModels() {
    }

    public record Vendor(UUID id, UUID entityId, String name, String email, String phone, String address,
                         String taxIdLast4, String taxClassification, boolean is1099Vendor,
                         UUID defaultExpenseAccountId, boolean isArchived, OffsetDateTime createdAt) {
    }

    public record BillLine(UUID id, int lineNo, String description, Money amount, UUID expenseAccountId) {
    }

    public record Bill(UUID id, UUID entityId, UUID vendorId, String vendorReference, LocalDate billDate,
                       LocalDate dueDate, String terms, String memo, Money total, Money amountPaid, Money balanceDue,
                       String status, UUID journalEntryId, List<BillLine> lines) {
    }

    public record BillPaymentApplication(UUID billId, Money amount) {
    }

    public record BillPayment(UUID id, UUID entityId, UUID vendorId, LocalDate paidDate, Money amount,
                              UUID paymentAccountId, String method, String reference, UUID journalEntryId,
                              List<BillPaymentApplication> applications) {
    }

    public record Form1099Candidate(UUID vendorId, String vendorName, Money paidInYear, boolean meetsThreshold,
                                    List<String> missingInformation) {
    }

    public record Form1099Report(int taxYear, boolean thresholdKnown, Money threshold, String thresholdSource,
                                 String note, List<Form1099Candidate> vendors) {
    }
}
