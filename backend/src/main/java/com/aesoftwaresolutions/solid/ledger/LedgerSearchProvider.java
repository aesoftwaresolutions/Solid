package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.search.SearchModels;
import com.aesoftwaresolutions.solid.search.SearchProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds journal entries and accounts. Runs inside the caller's organization scope, which the search service
 * has already opened, so row-level security applies exactly as it does everywhere else.
 */
@Component
class LedgerSearchProvider implements SearchProvider {

    private final JdbcClient db;
    private final OrgService orgs;

    LedgerSearchProvider(JdbcClient db, OrgService orgs) {
        this.db = db;
        this.orgs = orgs;
    }

    @Override
    public List<Found> search(UUID orgId, UUID entityId, SearchModels.Query query) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String like = "%" + query.text() + "%";
        long amount = query.amountMinor() == null ? -1 : query.amountMinor();

        String entryWhere = """
                from gl.journal_entry e
                where e.entity_id = ?
                  and (lower(coalesce(e.memo, '')) like ?
                       or exists (select 1 from gl.journal_line l
                                  where l.journal_entry_id = e.id and abs(l.amount_minor) = ?)
                       or exists (select 1 from gl.journal_line l
                                  where l.journal_entry_id = e.id and lower(coalesce(l.memo, '')) like ?))""";

        int entryTotal = db.sql("select count(*) " + entryWhere)
                .params(entityId, like, amount, like).query(Integer.class).single();
        List<SearchModels.Hit> entries = db.sql("""
                select e.id, e.entry_date, e.memo, e.status,
                       (select coalesce(sum(l.amount_minor), 0) from gl.journal_line l
                        where l.journal_entry_id = e.id and l.amount_minor > 0) as size_minor
                """ + entryWhere + " order by e.entry_date desc limit " + query.limitPerKind())
                .params(entityId, like, amount, like)
                .query((rs, n) -> new SearchModels.Hit("journal_entry", rs.getObject("id", UUID.class),
                        rs.getString("memo") == null ? "(no memo)" : rs.getString("memo"),
                        rs.getString("status"), rs.getObject("entry_date", java.time.LocalDate.class),
                        Money.ofMinor(rs.getLong("size_minor"), entity.baseCurrency()), "journal"))
                .list();

        String accountWhere = """
                from gl.account a
                where a.entity_id = ? and (lower(a.name) like ? or lower(a.code) like ?)""";
        int accountTotal = db.sql("select count(*) " + accountWhere)
                .params(entityId, like, like).query(Integer.class).single();
        List<SearchModels.Hit> accounts = db.sql("select a.id, a.code, a.name, a.type " + accountWhere
                + " order by a.code limit " + query.limitPerKind())
                .params(entityId, like, like)
                .query((rs, n) -> new SearchModels.Hit("account", rs.getObject("id", UUID.class),
                        rs.getString("code") + " " + rs.getString("name"), rs.getString("type"), null, null,
                        "accounts"))
                .list();

        return List.of(new Found("journal_entry", entryTotal, entries), new Found("account", accountTotal, accounts));
    }
}
