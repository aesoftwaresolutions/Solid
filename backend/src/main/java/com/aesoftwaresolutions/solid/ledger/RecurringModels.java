package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes for recurring entries. */
public final class RecurringModels {

    private RecurringModels() {
    }

    public record Line(int lineNo, UUID accountId, Money amount, String memo) {
    }

    /**
     * @param nextDate the next occurrence that has not been posted yet, or null when the template is finished
     */
    public record Recurring(UUID id, UUID entityId, String name, String memo, String frequency, LocalDate startDate,
                            LocalDate endDate, int dayOfMonth, boolean active, LocalDate nextDate, List<Line> lines) {
    }

    public record Posted(LocalDate occurrenceDate, UUID journalEntryId) {
    }

    public record Skipped(LocalDate occurrenceDate, String reason) {
    }

    public record RunResult(LocalDate through, List<Posted> posted, List<Skipped> skipped) {
    }
}
