package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Every entity in the organization on one line.
 *
 * <p>Each figure is taken from the report that already produces it — cash from the cash-flow service, profit
 * from the profit &amp; loss — so this page cannot drift away from the reports it summarises. The totals are a
 * plain sum, and only when every entity keeps its books in the same currency: Solid holds no exchange rates,
 * and adding two currencies would mean inventing one.
 */
@Service
public class OverviewService {

    static final String NOTE = "Totals are a plain sum of the entities, not a consolidation: amounts owed "
            + "between entities of this organization are still counted on both sides.";

    /** @param setUp false for an entity with no chart of accounts yet — its figures are zero, not a result */
    public record EntityLine(UUID entityId, String legalName, String kind, String currency, boolean setUp,
                             Money cash, Money netIncome, int draftEntries, int uncategorizedBankTransactions,
                             boolean needsAttention) {
    }

    public record Totals(String currency, Money cash, Money netIncome) {
    }

    /** @param totals null when the entities do not share one currency */
    public record Overview(UUID orgId, LocalDate from, LocalDate to, boolean mixedCurrencies,
                           List<EntityLine> entities, Totals totals, String note) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final ReportService reports;
    private final CashFlowService cashFlow;
    private final BankService bank;

    OverviewService(JdbcClient db, OrgScope orgScope, OrgService orgs, ReportService reports,
                    CashFlowService cashFlow, BankService bank) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.reports = reports;
        this.cashFlow = cashFlow;
        this.bank = bank;
    }

    public Overview overview(UUID orgId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        orgs.getOrganization(orgId);
        List<LegalEntity> entities = new ArrayList<>(orgs.listEntities(orgId));
        entities.sort((a, b) -> a.legalName().compareToIgnoreCase(b.legalName()));

        List<EntityLine> lines = new ArrayList<>();
        for (LegalEntity entity : entities) {
            String ccy = entity.baseCurrency();
            boolean setUp = accountCount(orgId, entity.id()) > 0;
            Money cash = setUp ? cashFlow.cashAsOf(orgId, entity.id(), to) : Money.zero(ccy);
            Money netIncome = setUp
                    ? reports.profitAndLoss(orgId, entity.id(), from, to).netIncome()
                    : Money.zero(ccy);
            int drafts = draftEntries(orgId, entity.id());
            int uncategorized = bank.countUncategorized(orgId, entity.id());
            lines.add(new EntityLine(entity.id(), entity.legalName(), entity.kind(), ccy, setUp, cash, netIncome,
                    drafts, uncategorized, drafts > 0 || uncategorized > 0));
        }

        Set<String> currencies = lines.stream().map(EntityLine::currency).collect(Collectors.toSet());
        boolean mixed = currencies.size() > 1;
        Totals totals = null;
        if (!mixed && !lines.isEmpty()) {
            String ccy = currencies.iterator().next();
            Money cash = Money.zero(ccy);
            Money netIncome = Money.zero(ccy);
            for (EntityLine line : lines) {
                cash = cash.add(line.cash());
                netIncome = netIncome.add(line.netIncome());
            }
            totals = new Totals(ccy, cash, netIncome);
        }
        return new Overview(orgId, from, to, mixed, List.copyOf(lines), totals, NOTE);
    }

    private int accountCount(UUID orgId, UUID entityId) {
        return orgScope.call(orgId, () -> db.sql("select count(*) from gl.account where entity_id = ?")
                .param(entityId).query(Integer.class).single());
    }

    private int draftEntries(UUID orgId, UUID entityId) {
        return orgScope.call(orgId, () -> db
                .sql("select count(*) from gl.journal_entry where entity_id = ? and status = 'draft'")
                .param(entityId).query(Integer.class).single());
    }
}
