package com.aesoftwaresolutions.solid.bank;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.search.SearchModels;
import com.aesoftwaresolutions.solid.search.SearchProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Finds imported bank transactions by what the bank called them, or by their amount. */
@Component
class BankSearchProvider implements SearchProvider {

    private final JdbcClient db;

    BankSearchProvider(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<Found> search(UUID orgId, UUID entityId, SearchModels.Query query) {
        String like = "%" + query.text() + "%";
        long amount = query.amountMinor() == null ? -1 : query.amountMinor();
        String where = """
                from bank.bank_txn t join bank.bank_account a on a.id = t.bank_account_id
                where a.entity_id = ? and (lower(t.description) like ? escape '\\' or abs(t.amount_minor) = ?)""";

        int total = db.sql("select count(*) " + where).params(entityId, like, amount).query(Integer.class).single();
        List<SearchModels.Hit> hits = db.sql("""
                select t.id, t.posted_date, t.description, t.amount_minor, t.currency, t.status, a.name as account_name
                """ + where + " order by t.posted_date desc limit " + query.limitPerKind())
                .params(entityId, like, amount)
                .query((rs, n) -> new SearchModels.Hit("bank_transaction", rs.getObject("id", UUID.class),
                        rs.getString("description"),
                        rs.getString("account_name") + " · " + rs.getString("status"),
                        rs.getObject("posted_date", LocalDate.class),
                        Money.ofMinor(rs.getLong("amount_minor"), rs.getString("currency")), "bank"))
                .list();
        return List.of(new Found("bank_transaction", total, hits));
    }
}
