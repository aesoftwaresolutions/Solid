package com.aesoftwaresolutions.solid.budget;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes for the budget module. */
public final class BudgetModels {

    private BudgetModels() {
    }

    public record BudgetLine(UUID accountId, String code, String name, String type, Money amount) {
    }

    public record Budget(UUID id, UUID entityId, LocalDate periodMonth, String currency, List<BudgetLine> lines,
                         Money budgetedIncome, Money budgetedExpenses, Money budgetedNet) {
    }

    /**
     * One account in the budget report.
     *
     * @param variance   written so positive is always good news: income above plan, or spending below plan
     * @param overBudget true only when an expense account spent more than it was given
     */
    public record ComparisonRow(UUID accountId, String code, String name, Money budget, Money actual, Money variance,
                                boolean overBudget) {
    }

    public record Totals(Money budgetedIncome, Money actualIncome, Money budgetedExpenses, Money actualExpenses,
                         Money budgetedNet, Money actualNet) {
    }

    public record BudgetVsActual(LocalDate periodMonth, String currency, List<ComparisonRow> income,
                                 List<ComparisonRow> expenses, Totals totals) {
    }
}
