package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.search.SearchModels;
import com.aesoftwaresolutions.solid.search.SearchProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Finds customers, invoices, vendors and bills — by name, number, reference, memo or amount. */
@Component
class BillingSearchProvider implements SearchProvider {

    private final JdbcClient db;

    BillingSearchProvider(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<Found> search(UUID orgId, UUID entityId, SearchModels.Query query) {
        String like = "%" + query.text() + "%";
        long amount = query.amountMinor() == null ? -1 : query.amountMinor();
        int limit = query.limitPerKind();

        String customerWhere = """
                from ar_ap.customer c
                where c.entity_id = ? and (lower(c.name) like ? or lower(coalesce(c.email, '')) like ?)""";
        int customerTotal = db.sql("select count(*) " + customerWhere)
                .params(entityId, like, like).query(Integer.class).single();
        List<SearchModels.Hit> customers = db.sql("select c.id, c.name, c.email " + customerWhere
                + " order by c.name limit " + limit)
                .params(entityId, like, like)
                .query((rs, n) -> new SearchModels.Hit("customer", rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("email"), null, null, "sales"))
                .list();

        String invoiceWhere = """
                from ar_ap.invoice i join ar_ap.customer c on c.id = i.customer_id
                where i.entity_id = ? and (lower(i.invoice_number) like ? or lower(coalesce(i.memo, '')) like ?
                                           or lower(c.name) like ? or i.total_minor = ?)""";
        int invoiceTotal = db.sql("select count(*) " + invoiceWhere)
                .params(entityId, like, like, like, amount).query(Integer.class).single();
        List<SearchModels.Hit> invoices = db.sql("""
                select i.id, i.invoice_number, i.issue_date, i.total_minor, i.currency, i.status, c.name
                """ + invoiceWhere + " order by i.issue_date desc limit " + limit)
                .params(entityId, like, like, like, amount)
                .query((rs, n) -> new SearchModels.Hit("invoice", rs.getObject("id", UUID.class),
                        "Invoice " + rs.getString("invoice_number"),
                        rs.getString("name") + " · " + rs.getString("status"),
                        rs.getObject("issue_date", LocalDate.class),
                        Money.ofMinor(rs.getLong("total_minor"), rs.getString("currency")), "sales"))
                .list();

        String vendorWhere = """
                from ar_ap.vendor v
                where v.entity_id = ? and (lower(v.name) like ? or lower(coalesce(v.email, '')) like ?)""";
        int vendorTotal = db.sql("select count(*) " + vendorWhere)
                .params(entityId, like, like).query(Integer.class).single();
        List<SearchModels.Hit> vendors = db.sql("select v.id, v.name, v.email " + vendorWhere
                + " order by v.name limit " + limit)
                .params(entityId, like, like)
                .query((rs, n) -> new SearchModels.Hit("vendor", rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("email"), null, null, "purchases"))
                .list();

        String billWhere = """
                from ar_ap.bill b join ar_ap.vendor v on v.id = b.vendor_id
                where b.entity_id = ? and (lower(coalesce(b.vendor_reference, '')) like ?
                                           or lower(coalesce(b.memo, '')) like ?
                                           or lower(v.name) like ? or b.total_minor = ?)""";
        int billTotal = db.sql("select count(*) " + billWhere)
                .params(entityId, like, like, like, amount).query(Integer.class).single();
        List<SearchModels.Hit> bills = db.sql("""
                select b.id, b.vendor_reference, b.bill_date, b.total_minor, b.currency, b.status, v.name
                """ + billWhere + " order by b.bill_date desc limit " + limit)
                .params(entityId, like, like, like, amount)
                .query((rs, n) -> new SearchModels.Hit("bill", rs.getObject("id", UUID.class),
                        rs.getString("vendor_reference") == null
                                ? "Bill from " + rs.getString("name")
                                : "Bill " + rs.getString("vendor_reference"),
                        rs.getString("name") + " · " + rs.getString("status"),
                        rs.getObject("bill_date", LocalDate.class),
                        Money.ofMinor(rs.getLong("total_minor"), rs.getString("currency")), "purchases"))
                .list();

        return List.of(new Found("customer", customerTotal, customers),
                new Found("invoice", invoiceTotal, invoices),
                new Found("vendor", vendorTotal, vendors),
                new Found("bill", billTotal, bills));
    }
}
