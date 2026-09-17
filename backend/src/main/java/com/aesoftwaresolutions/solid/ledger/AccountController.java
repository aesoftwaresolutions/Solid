package com.aesoftwaresolutions.solid.ledger;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<Account> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return accounts.list(orgId, entityId);
    }

    @GetMapping("/{accountId}")
    Account get(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID accountId) {
        return accounts.get(orgId, entityId, accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Account create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                   @Valid @RequestBody AccountRequests.CreateAccount body) {
        return accounts.create(orgId, entityId, body.code(), body.name(), body.type(), body.subtype(),
                body.parentId(), Boolean.TRUE.equals(body.isHeader()), body.taxLineCode());
    }

    @PatchMapping("/{accountId}")
    Account update(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID accountId,
                   @Valid @RequestBody AccountRequests.UpdateAccount body) {
        return accounts.update(orgId, entityId, accountId, body.name(), body.taxLineCode(), body.archived());
    }

    @PostMapping("/apply-template")
    @ResponseStatus(HttpStatus.CREATED)
    List<Account> applyTemplate(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                @Valid @RequestBody AccountRequests.ApplyTemplate body) {
        return accounts.applyTemplate(orgId, entityId, body.template());
    }
}
