package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    /** Net posted amount per account for a date range (debit-positive). */
    private record Balance(UUID accountId, String code, String name, String type, String subtype, long minor) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;

    ReportService(JdbcClient db, OrgScope orgScope, OrgService orgs) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    public Reports.TrialBalance trialBalance(UUID orgId, UUID entityId, LocalDate asOf) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        List<Balance> balances = orgScope.call(orgId, () -> balances(entityId, null, asOf));

        List<Reports.TrialBalanceRow> rows = new ArrayList<>();
        Money debits = Money.zero(ccy);
        Money credits = Money.zero(ccy);
        for (Balance b : balances) {
            Money debit = Money.ofMinor(Math.max(b.minor(), 0), ccy);
            Money credit = Money.ofMinor(Math.max(-b.minor(), 0), ccy);
            rows.add(new Reports.TrialBalanceRow(b.accountId(), b.code(), b.name(), b.type(), debit, credit));
            debits = debits.add(debit);
            credits = credits.add(credit);
        }
        return new Reports.TrialBalance(asOf, ccy, rows, debits, credits);
    }

    public Reports.ProfitAndLoss profitAndLoss(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        List<Balance> balances = orgScope.call(orgId, () -> balances(entityId, from, to));

        Reports.Section income = section(balances, b -> b.type().equals("income"), true, ccy);
        Reports.Section cogs = section(balances, b -> b.type().equals("expense") && "cogs".equals(b.subtype()), false, ccy);
        Reports.Section expenses = section(balances, b -> b.type().equals("expense") && !"cogs".equals(b.subtype()), false, ccy);
        Money grossProfit = income.total().subtract(cogs.total());
        Money netIncome = grossProfit.subtract(expenses.total());
        return new Reports.ProfitAndLoss(from, to, ccy, income, cogs, grossProfit, expenses, netIncome);
    }

    public Reports.BalanceSheet balanceSheet(UUID orgId, UUID entityId, LocalDate asOf) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        LocalDate fyStart = fiscalYearStart(asOf, entity.fiscalYearEnd());

        return orgScope.call(orgId, () -> {
            List<Balance> cumulative = balances(entityId, null, asOf);
            Reports.Section assets = section(cumulative, b -> b.type().equals("asset"), false, ccy);
            Reports.Section liabilities = section(cumulative, b -> b.type().equals("liability"), true, ccy);
            Reports.Section equityAccounts = section(cumulative, b -> b.type().equals("equity"), true, ccy);

            Money priorEarnings = netIncome(balances(entityId, null, fyStart.minusDays(1)), ccy);
            Money currentEarnings = netIncome(balances(entityId, fyStart, asOf), ccy);

            List<Reports.Row> equityRows = new ArrayList<>(equityAccounts.rows());
            if (!priorEarnings.isZero()) {
                equityRows.add(new Reports.Row(null, null, "Retained Earnings (prior years)", priorEarnings));
            }
            if (!currentEarnings.isZero()) {
                equityRows.add(new Reports.Row(null, null, "Current Year Earnings", currentEarnings));
            }
            Money equityTotal = equityAccounts.total().add(priorEarnings).add(currentEarnings);
            Reports.Section equity = new Reports.Section(List.copyOf(equityRows), equityTotal);
            Money liabilitiesAndEquity = liabilities.total().add(equityTotal);

            return new Reports.BalanceSheet(asOf, ccy, fyStart, assets, liabilities, equity, liabilitiesAndEquity,
                    assets.total().equals(liabilitiesAndEquity));
        });
    }

    /** First day of the fiscal year containing {@code date}, for a fiscal year ending in month {@code fyEndMonth}. */
    /** The first day of the fiscal year that contains {@code date}, for an entity whose year ends in that month. */
    public static LocalDate fiscalYearStart(LocalDate date, int fyEndMonth) {
        LocalDate candidate = LocalDate.of(date.getYear(), fyEndMonth, 1).plusMonths(1);
        return candidate.isAfter(date) ? candidate.minusYears(1) : candidate;
    }

    private static Money netIncome(List<Balance> balances, String ccy) {
        // income is credit (negative) and expenses debit (positive): net income = -(sum of both)
        long sum = balances.stream()
                .filter(b -> b.type().equals("income") || b.type().equals("expense"))
                .mapToLong(Balance::minor)
                .reduce(0L, Math::addExact);
        return Money.ofMinor(Math.negateExact(sum), ccy);
    }

    private static Reports.Section section(List<Balance> balances, Predicate<Balance> filter, boolean creditPositive,
                                           String ccy) {
        List<Reports.Row> rows = new ArrayList<>();
        Money total = Money.zero(ccy);
        for (Balance b : balances) {
            if (filter.test(b)) {
                Money amount = Money.ofMinor(creditPositive ? Math.negateExact(b.minor()) : b.minor(), ccy);
                rows.add(new Reports.Row(b.accountId(), b.code(), b.name(), amount));
                total = total.add(amount);
            }
        }
        return new Reports.Section(List.copyOf(rows), total);
    }

    private List<Balance> balances(UUID entityId, LocalDate from, LocalDate to) {
        return db.sql("""
                select a.id as account_id, a.code, a.name, a.type, a.subtype, sum(l.amount_minor) as minor
                from gl.journal_line l
                join gl.journal_entry e on e.id = l.journal_entry_id
                join gl.account a on a.id = l.account_id
                where e.entity_id = :entity
                  and e.status = 'posted'
                  and (cast(:from as date) is null or e.entry_date >= cast(:from as date))
                  and e.entry_date <= :to
                group by a.id, a.code, a.name, a.type, a.subtype
                having sum(l.amount_minor) <> 0
                order by a.code""")
                .param("entity", entityId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Balance(rs.getObject("account_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("type"), rs.getString("subtype"), rs.getLong("minor")))
                .list();
    }
}
