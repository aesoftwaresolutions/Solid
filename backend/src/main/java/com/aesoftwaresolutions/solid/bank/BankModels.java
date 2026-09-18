package com.aesoftwaresolutions.solid.bank;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response shapes for the bank module. */
public final class BankModels {

    private BankModels() {
    }

    public record BankAccount(UUID id, UUID entityId, UUID glAccountId, String name, String institution, String mask,
                              OffsetDateTime createdAt) {
    }

    public record ImportResult(UUID batchId, String format, int parsed, int imported, int duplicates) {
    }

    /** Amount is from the bank's point of view: positive = money into the account. */
    public record BankTransaction(UUID id, UUID bankAccountId, LocalDate postedDate, Money amount, String description,
                                  String status, UUID suggestedAccountId, String suggestionSource,
                                  UUID categoryAccountId, UUID journalEntryId) {
    }

    public record Rule(UUID id, UUID entityId, String contains, UUID accountId, int priority, OffsetDateTime createdAt) {
    }
}
