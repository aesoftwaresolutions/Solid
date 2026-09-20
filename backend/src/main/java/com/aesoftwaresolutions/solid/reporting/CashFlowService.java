package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Where the money actually went, by the direct method.
 *
 * <p>The change in the cash accounts <em>is</em> the net change, so this statement cannot disagree with the
 * balance sheet. Each cash movement is classified by what was on the other side of the same entry, which means
 * every figure can be traced to the accounts that produced it rather than reconciled from net income.
 */
@Service
public class CashFlowService {

    static final String NOTE = "Prepared by the direct method from the posted ledger: each movement of cash is "
            + "classified by what the money came from or went to. A preparer may present these figures "
            + "differently, and may use the indirect method on a filed return.";

    /** Where a movement belongs. */
    private enum Category { operating, investing, financing, unclassified }

    private record Counterpart(UUID accountId, String code, String name, String type, String subtype, long minor) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;

    CashFlowService(JdbcClient db, OrgScope orgScope, OrgService orgs) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    public Reports.CashFlow cashFlow(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();

        return orgScope.call(orgId, () -> {
            long opening = cashBalance(entityId, from.minusDays(1));
            long closing = cashBalance(entityId, to);

            Map<Category, Map<UUID, Long>> byCategory = new LinkedHashMap<>();
            Map<UUID, Counterpart> accountsSeen = new LinkedHashMap<>();
            for (Category category : Category.values()) {
                byCategory.put(category, new LinkedHashMap<>());
            }

            for (UUID entryId : entriesTouchingCash(entityId, from, to)) {
                long cashMovement = cashMovementOf(entryId);
                if (cashMovement == 0) {
                    // A transfer between two cash accounts: real, but it moves nothing in or out.
                    continue;
                }
                List<Counterpart> others = counterparts(entryId);
                allocate(cashMovement, others, byCategory, accountsSeen);
            }

            Reports.CashFlowSection operating = section(byCategory.get(Category.operating), accountsSeen, ccy);
            Reports.CashFlowSection investing = section(byCategory.get(Category.investing), accountsSeen, ccy);
            Reports.CashFlowSection financing = section(byCategory.get(Category.financing), accountsSeen, ccy);
            Reports.CashFlowSection unclassified = section(byCategory.get(Category.unclassified), accountsSeen, ccy);

            return new Reports.CashFlow(from, to, ccy, Money.ofMinor(opening, ccy), operating, investing, financing,
                    unclassified, Money.ofMinor(closing - opening, ccy), Money.ofMinor(closing, ccy), NOTE);
        });
    }

    /**
     * Splits one entry's cash movement across the categories of its other lines, in proportion to them. Works in
     * whole minor units and gives the remainder to the largest part, so the pieces always sum to the movement.
     */
    private static void allocate(long cashMovement, List<Counterpart> others,
                                 Map<Category, Map<UUID, Long>> byCategory, Map<UUID, Counterpart> accountsSeen) {
        long weightTotal = others.stream().mapToLong(c -> Math.abs(c.minor())).sum();
        if (others.isEmpty() || weightTotal == 0) {
            byCategory.get(Category.unclassified).merge(null, cashMovement, Long::sum);
            return;
        }
        long allocated = 0;
        Counterpart largest = others.get(0);
        for (Counterpart other : others) {
            accountsSeen.put(other.accountId(), other);
            // Integer arithmetic only: money never goes near a double. The truncated shares are topped up
            // with the leftover below, so the parts always sum to the movement exactly.
            long share = java.math.BigInteger.valueOf(cashMovement)
                    .multiply(java.math.BigInteger.valueOf(Math.abs(other.minor())))
                    .divide(java.math.BigInteger.valueOf(weightTotal))
                    .longValueExact();
            byCategory.get(categoryOf(other)).merge(other.accountId(), share, Long::sum);
            allocated += share;
            if (Math.abs(other.minor()) > Math.abs(largest.minor())) {
                largest = other;
            }
        }
        long leftover = cashMovement - allocated;
        if (leftover != 0) {
            byCategory.get(categoryOf(largest)).merge(largest.accountId(), leftover, Long::sum);
        }
    }

    private static Category categoryOf(Counterpart other) {
        String subtype = other.subtype() == null ? "" : other.subtype();
        return switch (other.type()) {
            case "income", "expense" -> Category.operating;
            case "equity" -> Category.financing;
            case "asset" -> switch (subtype) {
                case "fixed_asset", "contra_asset", "investment" -> Category.investing;
                // Receivables, inventory, prepaid: working capital, so operating.
                default -> Category.operating;
            };
            case "liability" -> switch (subtype) {
                case "loan", "long_term" -> Category.financing;
                // Payables, sales tax, accrued: working capital, so operating.
                default -> Category.operating;
            };
            default -> Category.unclassified;
        };
    }

    private Reports.CashFlowSection section(Map<UUID, Long> amounts, Map<UUID, Counterpart> accountsSeen,
                                            String ccy) {
        List<Reports.Row> rows = new ArrayList<>();
        long total = 0;
        for (Map.Entry<UUID, Long> entry : amounts.entrySet()) {
            Counterpart account = entry.getKey() == null ? null : accountsSeen.get(entry.getKey());
            rows.add(new Reports.Row(entry.getKey(), account == null ? null : account.code(),
                    account == null ? "Unclassified" : account.name(), Money.ofMinor(entry.getValue(), ccy)));
            total += entry.getValue();
        }
        rows.sort(Comparator.comparing(row -> row.code() == null ? "zzzz" : row.code()));
        return new Reports.CashFlowSection(List.copyOf(rows), Money.ofMinor(total, ccy));
    }

    // ---------------- queries (must run inside OrgScope) ----------------

    private static final String CASH = "a.type = 'asset' and coalesce(a.subtype, '') in ('bank', 'cash')";

    private long cashBalance(UUID entityId, LocalDate asOf) {
        return db.sql("""
                select coalesce(sum(l.amount_minor), 0)
                from gl.journal_line l
                join gl.journal_entry e on e.id = l.journal_entry_id
                join gl.account a on a.id = l.account_id
                where e.entity_id = ? and e.status = 'posted' and e.entry_date <= ? and """ + " " + CASH)
                .params(entityId, asOf).query(Long.class).single();
    }

    private List<UUID> entriesTouchingCash(UUID entityId, LocalDate from, LocalDate to) {
        return db.sql("""
                select distinct e.id from gl.journal_entry e
                join gl.journal_line l on l.journal_entry_id = e.id
                join gl.account a on a.id = l.account_id
                where e.entity_id = ? and e.status = 'posted' and e.entry_date between ? and ? and """ + " " + CASH)
                .params(entityId, from, to).query(UUID.class).list();
    }

    private long cashMovementOf(UUID entryId) {
        return db.sql("""
                select coalesce(sum(l.amount_minor), 0)
                from gl.journal_line l join gl.account a on a.id = l.account_id
                where l.journal_entry_id = ? and """ + " " + CASH)
                .param(entryId).query(Long.class).single();
    }

    private List<Counterpart> counterparts(UUID entryId) {
        return db.sql("""
                select l.account_id, a.code, a.name, a.type, a.subtype, sum(l.amount_minor) as minor
                from gl.journal_line l join gl.account a on a.id = l.account_id
                where l.journal_entry_id = ? and not (""" + CASH + """
                )
                group by l.account_id, a.code, a.name, a.type, a.subtype
                having sum(l.amount_minor) <> 0""")
                .param(entryId)
                .query((rs, n) -> new Counterpart(rs.getObject("account_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("type"), rs.getString("subtype"), rs.getLong("minor")))
                .list();
    }
}
