package com.aesoftwaresolutions.solid.budget;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.AccountType;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.reporting.ReportService;
import com.aesoftwaresolutions.solid.reporting.Reports;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Monthly budgets and the comparison against what the ledger actually recorded. */
@Service
public class BudgetService {

    /** One budget line as it arrives from the API. */
    public record NewLine(UUID accountId, Money amount) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final ReportService reports;

    BudgetService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts, ReportService reports) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.reports = reports;
    }

    public BudgetModels.Budget save(UUID orgId, UUID entityId, YearMonth month, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Map<UUID, Account> budgetable = budgetableAccounts(orgId, entityId);

        Map<UUID, Long> amounts = new LinkedHashMap<>();
        for (NewLine line : lines) {
            Account account = budgetable.get(line.accountId());
            if (account == null) {
                throw new IllegalArgumentException("Account " + line.accountId()
                        + " cannot be budgeted: budgets cover this entity's own income and expense accounts, "
                        + "and never header or archived accounts");
            }
            if (!line.amount().currency().equals(ccy)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Budget amounts must be in " + ccy);
            }
            if (line.amount().isNegative()) {
                throw new IllegalArgumentException("Budget amounts cannot be negative");
            }
            if (amounts.put(line.accountId(), line.amount().minorUnits()) != null) {
                throw new IllegalArgumentException("Account " + line.accountId() + " appears twice in the budget");
            }
        }

        LocalDate periodMonth = month.atDay(1);
        orgScope.run(orgId, () -> {
            UUID budgetId = db.sql("select id from pf.budget where entity_id = ? and period_month = ?")
                    .params(entityId, periodMonth).query(UUID.class).optional()
                    .orElse(null);
            if (budgetId == null) {
                budgetId = Ids.newId();
                db.sql("""
                        insert into pf.budget (id, org_id, entity_id, period_month, currency)
                        values (?, ?, ?, ?, ?)""")
                        .params(budgetId, orgId, entityId, periodMonth, ccy).update();
            } else {
                db.sql("update pf.budget set updated_at = now() where id = ?").param(budgetId).update();
                db.sql("delete from pf.budget_line where budget_id = ?").param(budgetId).update();
            }
            for (Map.Entry<UUID, Long> entry : amounts.entrySet()) {
                db.sql("""
                        insert into pf.budget_line (budget_id, account_id, amount_minor, org_id)
                        values (?, ?, ?, ?)""")
                        .params(budgetId, entry.getKey(), entry.getValue(), orgId).update();
            }
        });
        return get(orgId, entityId, month);
    }

    public BudgetModels.Budget get(UUID orgId, UUID entityId, YearMonth month) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        LocalDate periodMonth = month.atDay(1);
        Map<UUID, Account> byId = byId(accounts.list(orgId, entityId));

        return orgScope.call(orgId, () -> {
            UUID budgetId = db.sql("select id from pf.budget where entity_id = ? and period_month = ?")
                    .params(entityId, periodMonth).query(UUID.class).optional()
                    .orElseThrow(() -> new NotFoundException("No budget for " + month));
            List<BudgetModels.BudgetLine> lines = new ArrayList<>();
            Money income = Money.zero(ccy);
            Money expenses = Money.zero(ccy);
            for (Map<String, Object> row : db.sql("""
                    select account_id, amount_minor from pf.budget_line where budget_id = ?""")
                    .param(budgetId).query().listOfRows()) {
                UUID accountId = (UUID) row.get("account_id");
                Account account = byId.get(accountId);
                Money amount = Money.ofMinor(((Number) row.get("amount_minor")).longValue(), ccy);
                lines.add(new BudgetModels.BudgetLine(accountId, account.code(), account.name(),
                        account.type().name(), amount));
                if (account.type() == AccountType.income) {
                    income = income.add(amount);
                } else {
                    expenses = expenses.add(amount);
                }
            }
            lines.sort(Comparator.comparing(BudgetModels.BudgetLine::code));
            return new BudgetModels.Budget(budgetId, entityId, periodMonth, ccy, List.copyOf(lines), income, expenses,
                    income.subtract(expenses));
        });
    }

    public BudgetModels.BudgetVsActual compare(UUID orgId, UUID entityId, YearMonth month) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        LocalDate periodMonth = month.atDay(1);
        Map<UUID, Account> byId = byId(accounts.list(orgId, entityId));

        Map<UUID, Long> budgeted = orgScope.call(orgId, () -> {
            Map<UUID, Long> result = new LinkedHashMap<>();
            for (Map<String, Object> row : db.sql("""
                    select l.account_id, l.amount_minor from pf.budget_line l
                    join pf.budget b on b.id = l.budget_id
                    where b.entity_id = ? and b.period_month = ?""")
                    .params(entityId, periodMonth).query().listOfRows()) {
                result.put((UUID) row.get("account_id"), ((Number) row.get("amount_minor")).longValue());
            }
            return result;
        });

        // Actuals come from the P&L so the two reports can never tell different stories.
        Reports.ProfitAndLoss pnl = reports.profitAndLoss(orgId, entityId, periodMonth, month.atEndOfMonth());
        Map<UUID, Long> actual = new LinkedHashMap<>();
        actuals(pnl.income(), actual);
        actuals(pnl.costOfGoodsSold(), actual);
        actuals(pnl.expenses(), actual);

        List<BudgetModels.ComparisonRow> income = new ArrayList<>();
        List<BudgetModels.ComparisonRow> expenses = new ArrayList<>();
        Money budgetedIncome = Money.zero(ccy);
        Money actualIncome = Money.zero(ccy);
        Money budgetedExpenses = Money.zero(ccy);
        Money actualExpenses = Money.zero(ccy);

        List<UUID> accountIds = new ArrayList<>(budgeted.keySet());
        actual.keySet().stream().filter(id -> !budgeted.containsKey(id)).forEach(accountIds::add);
        for (UUID accountId : accountIds) {
            Account account = byId.get(accountId);
            if (account == null) {
                continue;
            }
            Money budget = Money.ofMinor(budgeted.getOrDefault(accountId, 0L), ccy);
            Money spent = Money.ofMinor(actual.getOrDefault(accountId, 0L), ccy);
            if (account.type() == AccountType.income) {
                income.add(new BudgetModels.ComparisonRow(accountId, account.code(), account.name(), budget, spent,
                        spent.subtract(budget), false));
                budgetedIncome = budgetedIncome.add(budget);
                actualIncome = actualIncome.add(spent);
            } else {
                expenses.add(new BudgetModels.ComparisonRow(accountId, account.code(), account.name(), budget, spent,
                        budget.subtract(spent), spent.compareTo(budget) > 0));
                budgetedExpenses = budgetedExpenses.add(budget);
                actualExpenses = actualExpenses.add(spent);
            }
        }
        income.sort(Comparator.comparing(BudgetModels.ComparisonRow::code));
        expenses.sort(Comparator.comparing(BudgetModels.ComparisonRow::code));

        BudgetModels.Totals totals = new BudgetModels.Totals(budgetedIncome, actualIncome, budgetedExpenses,
                actualExpenses, budgetedIncome.subtract(budgetedExpenses), actualIncome.subtract(actualExpenses));
        return new BudgetModels.BudgetVsActual(periodMonth, ccy, List.copyOf(income), List.copyOf(expenses), totals);
    }

    private static void actuals(Reports.Section section, Map<UUID, Long> into) {
        for (Reports.Row row : section.rows()) {
            if (row.accountId() != null) {
                into.merge(row.accountId(), row.amount().minorUnits(), Long::sum);
            }
        }
    }

    /** Income and expense accounts of this entity that a person can actually post to. */
    private Map<UUID, Account> budgetableAccounts(UUID orgId, UUID entityId) {
        Map<UUID, Account> result = new LinkedHashMap<>();
        for (Account account : accounts.list(orgId, entityId)) {
            boolean budgetable = !account.isHeader() && !account.isArchived()
                    && (account.type() == AccountType.income || account.type() == AccountType.expense);
            if (budgetable) {
                result.put(account.id(), account);
            }
        }
        return result;
    }

    private static Map<UUID, Account> byId(List<Account> list) {
        Map<UUID, Account> result = new LinkedHashMap<>();
        list.forEach(account -> result.put(account.id(), account));
        return result;
    }
}
