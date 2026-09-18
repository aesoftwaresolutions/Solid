package com.aesoftwaresolutions.solid.bank;

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
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/bank-accounts/{bankAccountId}/reconciliations")
class ReconciliationController {

    record Start(@NotNull LocalDate statementDate, @NotNull Money statementEndingBalance) {
    }

    record SetCleared(@NotEmpty @Size(max = 1000) List<UUID> lineIds, @NotNull Boolean cleared) {
    }

    private final ReconciliationService reconciliations;

    ReconciliationController(ReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReconciliationService.Status start(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                       @PathVariable UUID bankAccountId, @Valid @RequestBody Start body) {
        return reconciliations.start(orgId, entityId, bankAccountId, body.statementDate(), body.statementEndingBalance());
    }

    @GetMapping
    List<ReconciliationService.Status> history(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                               @PathVariable UUID bankAccountId) {
        return reconciliations.history(orgId, entityId, bankAccountId);
    }

    @GetMapping("/{reconciliationId}")
    ReconciliationService.Status get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                     @PathVariable UUID bankAccountId, @PathVariable UUID reconciliationId) {
        return reconciliations.get(orgId, entityId, bankAccountId, reconciliationId);
    }

    @GetMapping("/{reconciliationId}/candidates")
    List<ReconciliationService.Candidate> candidates(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                     @PathVariable UUID bankAccountId, @PathVariable UUID reconciliationId) {
        return reconciliations.candidates(orgId, entityId, bankAccountId, reconciliationId);
    }

    @PostMapping("/{reconciliationId}/cleared")
    ReconciliationService.Status setCleared(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                            @PathVariable UUID bankAccountId, @PathVariable UUID reconciliationId,
                                            @Valid @RequestBody SetCleared body) {
        return reconciliations.setCleared(orgId, entityId, bankAccountId, reconciliationId, body.lineIds(), body.cleared());
    }

    @PostMapping("/{reconciliationId}/complete")
    ReconciliationService.Status complete(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @PathVariable UUID bankAccountId, @PathVariable UUID reconciliationId) {
        return reconciliations.complete(orgId, entityId, bankAccountId, reconciliationId);
    }

    @PostMapping("/{reconciliationId}/undo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void undo(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID bankAccountId,
              @PathVariable UUID reconciliationId) {
        reconciliations.undo(orgId, entityId, bankAccountId, reconciliationId);
    }
}
