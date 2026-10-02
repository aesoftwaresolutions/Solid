package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class JournalRequests {

    private JournalRequests() {
    }

    record CreateEntry(
            @NotNull LocalDate entryDate,
            @Size(max = 500) String memo,
            Boolean post,
            @NotEmpty @Size(max = 500) List<@Valid LineRequest> lines) {
    }

    record LineRequest(@NotNull UUID accountId, @NotNull Money amount, @Size(max = 500) String memo,
                       UUID businessLineId) {
    }

    record Reverse(LocalDate entryDate, @Size(max = 500) String memo) {
    }

    record PeriodLock(@NotNull LocalDate lockedThrough) {
    }
}
