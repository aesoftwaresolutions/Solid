package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class OpeningBalanceController {

    record BalanceRequest(@NotNull UUID accountId, @NotNull Money amount) {
    }

    record OpeningBalances(@NotNull LocalDate asOfDate, UUID equityAccountId,
                           @NotEmpty @Size(max = 500) List<@Valid BalanceRequest> balances) {
    }

    private final OpeningBalanceService openingBalances;

    OpeningBalanceController(OpeningBalanceService openingBalances) {
        this.openingBalances = openingBalances;
    }

    @PostMapping("/opening-balances")
    @ResponseStatus(HttpStatus.CREATED)
    JournalEntry create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                        @Valid @RequestBody OpeningBalances body) {
        return openingBalances.create(orgId, entityId, body.asOfDate(), body.equityAccountId(),
                body.balances().stream().map(b -> new OpeningBalanceService.NewBalance(b.accountId(), b.amount()))
                        .toList());
    }

    @GetMapping("/opening-balances")
    JournalEntry get(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return openingBalances.get(orgId, entityId);
    }
}
