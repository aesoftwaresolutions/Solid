package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The profit and loss split by business line (spec 069): what each facet of the business earned and cost. It reads
 * the same posted lines as the plain P&amp;L and only groups them differently, so its total column is the P&amp;L.
 */
@Service
public class BusinessLineReportService {

    static final String UNASSIGNED_NAME = "Shared / overhead";

    private record Cell(UUID accountId, String code, String name, String type, String subtype, UUID businessLineId,
                        long minor) {
    }

    private record LineInfo(UUID id, String name, boolean archived) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;

    BusinessLineReportService(JdbcClient db, OrgScope orgScope, OrgService orgs) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    public Reports.ProfitAndLossByBusinessLine profitAndLoss(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        return orgScope.call(orgId, () -> {
            List<Cell> cells = cells(entityId, from, to);

            // Columns: every active business line, plus any archived one that has activity in the range, by name;
            // then the unassigned column, always last and always present.
            List<LineInfo> lines = db.sql("""
                    select id, name, is_archived from gl.business_line where entity_id = ? order by lower(name), id""")
                    .param(entityId)
                    .query((rs, n) -> new LineInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getBoolean("is_archived")))
                    .list();
            java.util.Set<UUID> active = new java.util.HashSet<>();
            cells.forEach(c -> active.add(c.businessLineId()));
            List<LineInfo> columns = new ArrayList<>();
            for (LineInfo line : lines) {
                if (!line.archived() || active.contains(line.id())) {
                    columns.add(line);
                }
            }
            columns.add(new LineInfo(null, UNASSIGNED_NAME, false));
            Map<UUID, Integer> index = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                index.put(columns.get(i).id(), i);
            }

            // Rows: one per account with any activity, in account-code order, amounts signed the way the P&L
            // shows them (income credit-positive, costs debit-positive).
            Map<UUID, long[]> byAccount = new LinkedHashMap<>();
            Map<UUID, Cell> accountInfo = new HashMap<>();
            for (Cell c : cells) {
                long[] amounts = byAccount.computeIfAbsent(c.accountId(), k -> new long[columns.size()]);
                accountInfo.putIfAbsent(c.accountId(), c);
                int col = index.get(c.businessLineId());
                long signed = c.type().equals("income") ? Math.negateExact(c.minor()) : c.minor();
                amounts[col] = Math.addExact(amounts[col], signed);
            }

            long[] income = new long[columns.size()];
            long[] cogs = new long[columns.size()];
            long[] expenses = new long[columns.size()];
            List<Reports.BusinessLineRow> rows = new ArrayList<>();
            for (Map.Entry<UUID, long[]> e : byAccount.entrySet()) {
                Cell info = accountInfo.get(e.getKey());
                String section = section(info);
                long[] target = switch (section) {
                    case "income" -> income;
                    case "costOfGoodsSold" -> cogs;
                    default -> expenses;
                };
                long rowTotal = 0;
                List<Money> amounts = new ArrayList<>(columns.size());
                for (int i = 0; i < columns.size(); i++) {
                    long v = e.getValue()[i];
                    target[i] = Math.addExact(target[i], v);
                    rowTotal = Math.addExact(rowTotal, v);
                    amounts.add(Money.ofMinor(v, ccy));
                }
                rows.add(new Reports.BusinessLineRow(info.accountId(), info.code(), info.name(), section,
                        List.copyOf(amounts), Money.ofMinor(rowTotal, ccy)));
            }

            List<Reports.BusinessLineColumn> out = new ArrayList<>(columns.size());
            long totalIncome = 0;
            long totalCogs = 0;
            long totalExpenses = 0;
            for (int i = 0; i < columns.size(); i++) {
                LineInfo line = columns.get(i);
                out.add(column(line.id(), line.name(), line.archived(), income[i], cogs[i], expenses[i], ccy));
                totalIncome = Math.addExact(totalIncome, income[i]);
                totalCogs = Math.addExact(totalCogs, cogs[i]);
                totalExpenses = Math.addExact(totalExpenses, expenses[i]);
            }
            Reports.BusinessLineColumn total = column(null, "Total", false, totalIncome, totalCogs, totalExpenses, ccy);
            return new Reports.ProfitAndLossByBusinessLine(from, to, ccy, List.copyOf(out), List.copyOf(rows), total);
        });
    }

    /** The report as CSV: one row per account, then the section totals, one column per business line. */
    public String csv(Reports.ProfitAndLossByBusinessLine report) {
        StringBuilder out = new StringBuilder("Section,Code,Account");
        report.columns().forEach(c -> out.append(',').append(cell(c.name())));
        out.append(",Total\n");
        for (Reports.BusinessLineRow row : report.rows()) {
            out.append(row.section()).append(',').append(cell(row.code())).append(',').append(cell(row.name()));
            row.amounts().forEach(a -> out.append(',').append(a.toDecimalString()));
            out.append(',').append(row.total().toDecimalString()).append("\n");
        }
        totalRow(out, "Total income", report, Reports.BusinessLineColumn::income);
        totalRow(out, "Total cost of goods sold", report, Reports.BusinessLineColumn::costOfGoodsSold);
        totalRow(out, "Gross profit", report, Reports.BusinessLineColumn::grossProfit);
        totalRow(out, "Total expenses", report, Reports.BusinessLineColumn::expenses);
        totalRow(out, "Net income", report, Reports.BusinessLineColumn::netIncome);
        return out.toString();
    }

    private static void totalRow(StringBuilder out, String label, Reports.ProfitAndLossByBusinessLine report,
                                 java.util.function.Function<Reports.BusinessLineColumn, Money> pick) {
        out.append("total,,").append(cell(label));
        report.columns().forEach(c -> out.append(',').append(pick.apply(c).toDecimalString()));
        out.append(',').append(pick.apply(report.total()).toDecimalString()).append("\n");
    }

    private static String cell(String value) {
        return com.aesoftwaresolutions.solid.common.CsvCells.cell(value);
    }

    private static String section(Cell c) {
        if (c.type().equals("income")) {
            return "income";
        }
        return "cogs".equals(c.subtype()) ? "costOfGoodsSold" : "expenses";
    }

    private static Reports.BusinessLineColumn column(UUID id, String name, boolean archived, long income, long cogs,
                                                     long expenses, String ccy) {
        long gross = Math.subtractExact(income, cogs);
        long net = Math.subtractExact(gross, expenses);
        return new Reports.BusinessLineColumn(id, name, archived, Money.ofMinor(income, ccy), Money.ofMinor(cogs, ccy),
                Money.ofMinor(gross, ccy), Money.ofMinor(expenses, ccy), Money.ofMinor(net, ccy));
    }

    private List<Cell> cells(UUID entityId, LocalDate from, LocalDate to) {
        return db.sql("""
                select a.id as account_id, a.code, a.name, a.type, a.subtype, l.business_line_id,
                       sum(l.amount_minor) as minor
                from gl.journal_line l
                join gl.journal_entry e on e.id = l.journal_entry_id
                join gl.account a on a.id = l.account_id
                where e.entity_id = :entity
                  and e.status = 'posted'
                  and a.type in ('income', 'expense')
                  and e.entry_date >= :from
                  and e.entry_date <= :to
                group by a.id, a.code, a.name, a.type, a.subtype, l.business_line_id
                having sum(l.amount_minor) <> 0
                order by a.code""")
                .param("entity", entityId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Cell(rs.getObject("account_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("type"), rs.getString("subtype"),
                        rs.getObject("business_line_id", UUID.class), rs.getLong("minor")))
                .list();
    }
}
