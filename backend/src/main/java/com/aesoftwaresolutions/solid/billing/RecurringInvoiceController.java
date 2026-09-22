package com.aesoftwaresolutions.solid.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/recurring-invoices")
class RecurringInvoiceController {

    record LineRequest(@NotBlank @Size(max = 300) String description, @NotNull BigDecimal quantity,
                       @NotNull com.aesoftwaresolutions.solid.money.Money unitPrice,
                       @NotNull UUID incomeAccountId, UUID taxRateId) {
    }

    record CreateRequest(@NotNull UUID customerId, @NotBlank @Size(max = 120) String name,
                         @Size(max = 500) String memo,
                         @NotNull @Pattern(regexp = "due_on_receipt|net_15|net_30|net_60") String terms,
                         @NotNull @Pattern(regexp = "monthly|quarterly|annual") String frequency,
                         @NotNull LocalDate startDate, LocalDate endDate, Integer dayOfMonth,
                         @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    private final RecurringInvoiceService recurring;

    RecurringInvoiceController(RecurringInvoiceService recurring) {
        this.recurring = recurring;
    }

    @GetMapping
    List<RecurringInvoiceModels.RecurringInvoice> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return recurring.list(orgId, entityId);
    }

    @GetMapping("/{id}")
    RecurringInvoiceModels.RecurringInvoice get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                @PathVariable UUID id) {
        return recurring.get(orgId, entityId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecurringInvoiceModels.RecurringInvoice create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                   @Valid @RequestBody CreateRequest body) {
        return recurring.create(orgId, entityId, body.customerId(), body.name(), body.memo(), body.terms(),
                body.frequency(), body.startDate(), body.endDate(), body.dayOfMonth(),
                body.lines().stream().map(line -> new RecurringInvoiceService.NewLine(line.description(),
                        line.quantity(), line.unitPrice(), line.incomeAccountId(), line.taxRateId())).toList());
    }

    @PostMapping("/{id}/deactivate")
    RecurringInvoiceModels.RecurringInvoice deactivate(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                       @PathVariable UUID id) {
        return recurring.deactivate(orgId, entityId, id);
    }

    /** Creates what is due. Nothing runs on a timer; this is the call a person or a cron job makes. */
    @PostMapping("/run")
    RecurringInvoiceModels.RunResult run(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return recurring.run(orgId, entityId, through == null ? LocalDate.now() : through);
    }
}
