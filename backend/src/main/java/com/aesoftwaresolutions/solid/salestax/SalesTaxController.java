package com.aesoftwaresolutions.solid.salestax;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class SalesTaxController {

    record CreateRate(@NotBlank @Size(max = 120) String jurisdiction, @NotNull BigDecimal ratePercent,
                      @NotNull UUID liabilityAccountId, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                      @Size(max = 500) String note) {
    }

    private final SalesTaxService salesTax;

    SalesTaxController(SalesTaxService salesTax) {
        this.salesTax = salesTax;
    }

    @GetMapping("/sales-tax-rates")
    List<SalesTaxModels.Rate> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return salesTax.list(orgId, entityId);
    }

    @PostMapping("/sales-tax-rates")
    @ResponseStatus(HttpStatus.CREATED)
    SalesTaxModels.Rate create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                               @Valid @RequestBody CreateRate body) {
        return salesTax.create(orgId, entityId, body.jurisdiction(), body.ratePercent(), body.liabilityAccountId(),
                body.effectiveFrom(), body.effectiveTo(), body.note());
    }

    @PostMapping("/sales-tax-rates/{rateId}/deactivate")
    SalesTaxModels.Rate deactivate(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @PathVariable UUID rateId) {
        return salesTax.deactivate(orgId, entityId, rateId);
    }

    @GetMapping("/reports/sales-tax")
    SalesTaxModels.SalesTaxReport report(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return salesTax.report(orgId, entityId, from, to);
    }
}
