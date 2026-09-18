package com.aesoftwaresolutions.solid.budget;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class BudgetController {

    record LineRequest(@NotNull UUID accountId, @NotNull Money amount) {
    }

    record BudgetRequest(@NotNull @Size(max = 500) List<@Valid LineRequest> lines) {
    }

    private final BudgetService budgets;

    BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @PutMapping("/budgets/{month}")
    BudgetModels.Budget save(@PathVariable UUID orgId, @PathVariable UUID entityId,
                             @PathVariable @Pattern(regexp = "\\d{4}-\\d{2}") String month,
                             @Valid @RequestBody BudgetRequest body) {
        return budgets.save(orgId, entityId, parse(month),
                body.lines().stream().map(l -> new BudgetService.NewLine(l.accountId(), l.amount())).toList());
    }

    @GetMapping("/budgets/{month}")
    BudgetModels.Budget get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                            @PathVariable @Pattern(regexp = "\\d{4}-\\d{2}") String month) {
        return budgets.get(orgId, entityId, parse(month));
    }

    @GetMapping("/reports/budget-vs-actual")
    BudgetModels.BudgetVsActual compare(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @RequestParam String month) {
        return budgets.compare(orgId, entityId, parse(month));
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("month must look like 2026-10");
        }
    }
}
