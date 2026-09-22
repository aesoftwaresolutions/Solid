package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/credit-notes")
class CreditNoteController {

    /** {@code invoiceLineId} is optional: with it the credit is a return and reverses that line's tax. */
    record LineRequest(@NotBlank @Size(max = 300) String description, @NotNull BigDecimal quantity,
                       @NotNull Money unitPrice, @NotNull UUID incomeAccountId, UUID invoiceLineId) {
    }

    record CreditNoteRequest(@NotNull UUID customerId, @NotNull LocalDate issueDate,
                             @Size(max = 40) String creditNumber, @Size(max = 500) String memo,
                             @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    record ApplyRequest(@NotNull UUID invoiceId, @NotNull Money amount) {
    }

    private final CreditNoteService credits;

    CreditNoteController(CreditNoteService credits) {
        this.credits = credits;
    }

    @GetMapping
    List<BillingModels.CreditNote> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return credits.list(orgId, entityId);
    }

    @GetMapping("/{creditNoteId}")
    BillingModels.CreditNote get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                 @PathVariable UUID creditNoteId) {
        return credits.get(orgId, entityId, creditNoteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BillingModels.CreditNote create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                    @Valid @RequestBody CreditNoteRequest body) {
        return credits.create(orgId, entityId, body.customerId(), body.issueDate(), body.creditNumber(),
                body.memo(), lines(body));
    }

    @PatchMapping("/{creditNoteId}")
    BillingModels.CreditNote update(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                    @PathVariable UUID creditNoteId, @Valid @RequestBody CreditNoteRequest body) {
        return credits.update(orgId, entityId, creditNoteId, body.customerId(), body.issueDate(), body.memo(),
                lines(body));
    }

    @PostMapping("/{creditNoteId}/issue")
    BillingModels.CreditNote issue(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @PathVariable UUID creditNoteId) {
        return credits.issue(orgId, entityId, creditNoteId);
    }

    @PostMapping("/{creditNoteId}/void")
    BillingModels.CreditNote voidCredit(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @PathVariable UUID creditNoteId) {
        return credits.voidCredit(orgId, entityId, creditNoteId);
    }

    @PostMapping("/{creditNoteId}/applications")
    BillingModels.CreditNote apply(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @PathVariable UUID creditNoteId, @Valid @RequestBody ApplyRequest body) {
        return credits.apply(orgId, entityId, creditNoteId, body.invoiceId(), body.amount());
    }

    @DeleteMapping("/{creditNoteId}/applications/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unapply(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID creditNoteId,
                 @PathVariable UUID applicationId) {
        credits.unapply(orgId, entityId, creditNoteId, applicationId);
    }

    private static List<CreditNoteService.NewLine> lines(CreditNoteRequest body) {
        return body.lines().stream()
                .map(line -> new CreditNoteService.NewLine(line.description(), line.quantity(), line.unitPrice(),
                        line.incomeAccountId(), line.invoiceLineId()))
                .toList();
    }
}
