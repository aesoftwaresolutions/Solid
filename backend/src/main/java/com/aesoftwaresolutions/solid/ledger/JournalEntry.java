package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record JournalEntry(
        UUID id,
        UUID orgId,
        UUID entityId,
        LocalDate entryDate,
        String memo,
        String source,
        Status status,
        UUID reversesEntryId,
        Long postingSeq,
        String hash,
        OffsetDateTime postedAt,
        OffsetDateTime createdAt,
        List<Line> lines) {

    public enum Status { draft, posted }

    /** Positive amount = debit, negative = credit. */
    /** @param businessLineId the facet of the business this line belongs to (spec 069); null = shared/unassigned */
    public record Line(int lineNo, UUID accountId, Money amount, String memo, UUID businessLineId) {
    }
}
