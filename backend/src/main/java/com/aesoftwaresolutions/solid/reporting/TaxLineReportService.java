package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.common.CsvCells;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.tax.TaxLine;
import com.aesoftwaresolutions.solid.tax.TaxLineCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TaxLineReportService {

    private record Row(UUID accountId, String code, String name, String type, String taxLineCode, long minor) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final TaxLineCatalog catalog;
    private final BankService bank;

    TaxLineReportService(JdbcClient db, OrgScope orgScope, OrgService orgs, TaxLineCatalog catalog, BankService bank) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.catalog = catalog;
        this.bank = bank;
    }

    public TaxLineReport report(UUID orgId, UUID entityId, int taxYear) {
        if (taxYear < 2000 || taxYear > 2100) {
            throw new IllegalArgumentException("taxYear must be between 2000 and 2100");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        LocalDate to = LocalDate.of(taxYear, entity.fiscalYearEnd(), 1).plusMonths(1).minusDays(1);
        LocalDate from = to.minusYears(1).plusDays(1);

        return orgScope.call(orgId, () -> {
            List<Row> rows = db.sql("""
                    select a.id as account_id, a.code, a.name, a.type, a.default_tax_line_code as tax_line_code,
                           sum(l.amount_minor) as minor
                    from gl.journal_line l
                    join gl.journal_entry e on e.id = l.journal_entry_id
                    join gl.account a on a.id = l.account_id
                    where e.entity_id = :entity and e.status = 'posted'
                      and e.entry_date between :from and :to
                      and a.type in ('income', 'expense')
                    group by a.id, a.code, a.name, a.type, a.default_tax_line_code
                    having sum(l.amount_minor) <> 0
                    order by a.code""")
                    .param("entity", entityId).param("from", from).param("to", to)
                    .query((rs, n) -> new Row(rs.getObject("account_id", UUID.class), rs.getString("code"),
                            rs.getString("name"), rs.getString("type"), rs.getString("tax_line_code"), rs.getLong("minor")))
                    .list();

            Map<String, List<Row>> byLine = new LinkedHashMap<>();
            List<TaxLineReport.UnmappedAccount> unmapped = new ArrayList<>();
            Money income = Money.zero(ccy);
            Money cogs = Money.zero(ccy);
            Money expenses = Money.zero(ccy);

            for (Row row : rows) {
                Money amount = signed(row, ccy);
                if (row.taxLineCode() == null || catalog.find(row.taxLineCode()).isEmpty()) {
                    unmapped.add(new TaxLineReport.UnmappedAccount(row.accountId(), row.code(), row.name(), row.type(), amount));
                    if (row.type().equals("income")) {
                        income = income.add(amount);
                    } else {
                        expenses = expenses.add(amount);
                    }
                    continue;
                }
                byLine.computeIfAbsent(row.taxLineCode(), k -> new ArrayList<>()).add(row);
                TaxLine line = catalog.find(row.taxLineCode()).orElseThrow();
                switch (line.kind()) {
                    case income -> income = income.add(amount);
                    case cogs -> cogs = cogs.add(amount);
                    case expense -> expenses = expenses.add(amount);
                }
            }

            List<TaxLineReport.Line> lines = new ArrayList<>();
            for (Map.Entry<String, List<Row>> entry : byLine.entrySet()) {
                TaxLine line = catalog.find(entry.getKey()).orElseThrow();
                Money total = Money.zero(ccy);
                List<TaxLineReport.AccountAmount> accounts = new ArrayList<>();
                for (Row row : entry.getValue()) {
                    Money amount = signed(row, ccy);
                    total = total.add(amount);
                    accounts.add(new TaxLineReport.AccountAmount(row.accountId(), row.code(), row.name(), amount));
                }
                lines.add(new TaxLineReport.Line(line.code(), line.form(), line.line(), line.label(), line.kind().name(),
                        total, List.copyOf(accounts)));
            }
            lines.sort(Comparator.comparing(TaxLineReport.Line::form).thenComparing(l -> lineOrder(l.line())));

            int drafts = db.sql("select count(*) from gl.journal_entry where entity_id = ? and status = 'draft'")
                    .param(entityId).query(Integer.class).single();
            int uncategorized = bank.countUncategorized(orgId, entityId);
            TaxLineReport.Readiness readiness = new TaxLineReport.Readiness(drafts, uncategorized, unmapped.size(),
                    drafts == 0 && uncategorized == 0 && unmapped.isEmpty());

            TaxLineReport.Totals totals = new TaxLineReport.Totals(income, cogs, expenses,
                    income.subtract(cogs).subtract(expenses));
            return new TaxLineReport(taxYear, from, to, ccy, List.copyOf(lines), List.copyOf(unmapped), totals, readiness);
        });
    }

    public String csv(TaxLineReport report) {
        StringBuilder sb = new StringBuilder("Section,Form,Line,Label,Account Code,Account Name,Amount\n");
        for (TaxLineReport.Line line : report.lines()) {
            for (TaxLineReport.AccountAmount account : line.accounts()) {
                sb.append(row(line.kind(), line.form(), line.line(), line.label(), account.code(), account.name(),
                        account.amount().toDecimalString()));
            }
            sb.append(row(line.kind(), line.form(), line.line(), line.label() + " — line total", "", "",
                    line.amount().toDecimalString()));
        }
        for (TaxLineReport.UnmappedAccount account : report.unmapped()) {
            sb.append(row("unmapped", "", "", "No tax line mapped", account.code(), account.name(),
                    account.amount().toDecimalString()));
        }
        sb.append('\n');
        sb.append(row("totals", "", "", "Income", "", "", report.totals().income().toDecimalString()));
        sb.append(row("totals", "", "", "Cost of goods sold", "", "", report.totals().costOfGoodsSold().toDecimalString()));
        sb.append(row("totals", "", "", "Expenses", "", "", report.totals().expenses().toDecimalString()));
        sb.append(row("totals", "", "", "Net profit", "", "", report.totals().netProfit().toDecimalString()));
        sb.append(row("readiness", "", "", "Draft entries", "", "", String.valueOf(report.readiness().draftEntries())));
        sb.append(row("readiness", "", "", "Uncategorized bank transactions", "", "",
                String.valueOf(report.readiness().uncategorizedBankTransactions())));
        sb.append(row("readiness", "", "", "Accounts with no tax line", "", "",
                String.valueOf(report.readiness().unmappedAccounts())));
        return sb.toString();
    }

    /** Income is credit-positive, expenses debit-positive, matching the profit & loss report. */
    private static Money signed(Row row, String ccy) {
        long minor = row.type().equals("income") ? Math.negateExact(row.minor()) : row.minor();
        return Money.ofMinor(minor, ccy);
    }

    /** Sorts "L1", "L9", "L16a", "L16b", "L30" in form order rather than alphabetically. */
    private static String lineOrder(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        String suffix = line.replaceAll("[^a-zA-Z]", "").toLowerCase(java.util.Locale.ROOT);
        return String.format("%04d%s", digits.isEmpty() ? 0 : Integer.parseInt(digits), suffix);
    }

    /** Through the shared writer, so an account name cannot become a formula in the export (spec 065, row 9). */
    private static String row(String... cells) {
        return CsvCells.row((Object[]) cells);
    }
}
