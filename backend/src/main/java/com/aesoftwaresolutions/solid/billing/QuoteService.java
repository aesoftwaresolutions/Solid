package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Quotes: a price you offered, not money anyone owes.
 *
 * <p>A quote posts nothing and appears in no report. Accepting it changes only the quote; converting it makes
 * a <em>draft</em> invoice with the same lines, because the numbers deserve a last look before they go out and
 * because until an invoice is issued there is still nothing in the books.
 */
@Service
public class QuoteService {

    public record NewLine(String description, BigDecimal quantity, Money unitPrice, UUID incomeAccountId) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final BillingService billing;

    QuoteService(JdbcClient db, OrgScope orgScope, OrgService orgs, BillingService billing) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.billing = billing;
    }

    public QuoteModels.Quote create(UUID orgId, UUID entityId, UUID customerId, LocalDate issueDate,
                                    LocalDate validUntil, String quoteNumber, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        billing.getCustomer(orgId, entityId, customerId);
        if (validUntil != null && validUntil.isBefore(issueDate)) {
            throw new IllegalArgumentException("'validUntil' must not be before the issue date");
        }
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            String number = quoteNumber == null || quoteNumber.isBlank() ? nextQuoteNumber(entityId)
                    : quoteNumber.trim();
            requireNumberFree(entityId, number);
            db.sql("""
                    insert into ar_ap.quote (id, org_id, entity_id, customer_id, quote_number, issue_date,
                                             valid_until, memo, total_minor, currency, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'draft')""")
                    .params(id, orgId, entityId, customerId, number, issueDate, validUntil, memo,
                            entity.baseCurrency())
                    .update();
            replaceLines(orgId, id, entity.baseCurrency(), lines);
            return load(entityId, id, entity.baseCurrency());
        });
    }

    public QuoteModels.Quote update(UUID orgId, UUID entityId, UUID quoteId, UUID customerId, LocalDate issueDate,
                                    LocalDate validUntil, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            QuoteModels.Quote existing = load(entityId, quoteId, entity.baseCurrency());
            requireStatus(existing, "Only a draft quote can be changed", "draft");
            db.sql("""
                    update ar_ap.quote set customer_id = ?, issue_date = ?, valid_until = ?, memo = ?
                    where entity_id = ? and id = ?""")
                    .params(customerId, issueDate, validUntil, memo, entityId, quoteId).update();
            replaceLines(orgId, quoteId, entity.baseCurrency(), lines);
            return load(entityId, quoteId, entity.baseCurrency());
        });
    }

    public List<QuoteModels.Quote> list(UUID orgId, UUID entityId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(
                "select id from ar_ap.quote where entity_id = ? order by issue_date desc, quote_number desc")
                .param(entityId).query(UUID.class).list().stream()
                .map(id -> load(entityId, id, entity.baseCurrency()))
                .toList());
    }

    public QuoteModels.Quote get(UUID orgId, UUID entityId, UUID quoteId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, quoteId, entity.baseCurrency()));
    }

    /**
     * The quote rendered as a PDF the customer can read. It is deliberately not invoice-shaped: no due date,
     * no terms, no amount due.
     */
    public byte[] quotePdf(UUID orgId, UUID entityId, UUID quoteId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        QuoteModels.Quote quote = orgScope.call(orgId, () -> load(entityId, quoteId, entity.baseCurrency()));
        BillingModels.Customer customer = billing.getCustomer(orgId, entityId, quote.customerId());
        String invoiceNumber = quote.invoiceId() == null ? null
                : billing.getInvoice(orgId, entityId, quote.invoiceId()).invoiceNumber();
        return QuotePdf.render(entity, customer, quote, invoiceNumber, billing.letterhead(orgId, entityId),
                billing.logoBytes(orgId, entityId));
    }

    public QuoteModels.Quote send(UUID orgId, UUID entityId, UUID quoteId) {
        return transition(orgId, entityId, quoteId, "sent", "Only a draft quote can be sent", "draft");
    }

    public QuoteModels.Quote accept(UUID orgId, UUID entityId, UUID quoteId) {
        return transition(orgId, entityId, quoteId, "accepted",
                "Only a draft or sent quote can be accepted", "draft", "sent");
    }

    public QuoteModels.Quote decline(UUID orgId, UUID entityId, UUID quoteId, String reason) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            QuoteModels.Quote quote = load(entityId, quoteId, entity.baseCurrency());
            requireStatus(quote, "Only a draft or sent quote can be declined", "draft", "sent");
            db.sql("update ar_ap.quote set status = 'declined', declined_reason = ? where entity_id = ? and id = ?")
                    .params(reason, entityId, quoteId).update();
            return load(entityId, quoteId, entity.baseCurrency());
        });
    }

    /** Turns an accepted quote into a draft invoice. The quote keeps the id of the invoice it became. */
    public BillingModels.Invoice convert(UUID orgId, UUID entityId, UUID quoteId, LocalDate issueDate,
                                         String terms) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        QuoteModels.Quote quote = get(orgId, entityId, quoteId);
        if (quote.status().equals("converted")) {
            throw new BusinessRuleException("QUOTE_ALREADY_CONVERTED",
                    "That quote is already invoice " + quote.invoiceId());
        }
        if (quote.expired()) {
            throw new BusinessRuleException("QUOTE_EXPIRED",
                    "That quote was only valid until " + quote.validUntil() + " and nobody accepted it in time. "
                            + "Change the date if the price still stands.");
        }
        requireStatus(quote, "Only an accepted quote can be turned into an invoice", "accepted");
        if (billing.getCustomer(orgId, entityId, quote.customerId()).isArchived()) {
            throw new BusinessRuleException("CUSTOMER_ARCHIVED",
                    "That customer is archived, so there is nobody to invoice");
        }

        List<BillingService.NewLine> lines = quote.lines().stream()
                .map(line -> new BillingService.NewLine(line.description(), line.quantity(), line.unitPrice(),
                        line.incomeAccountId(), null))
                .toList();
        BillingModels.Invoice invoice = billing.createInvoice(orgId, entityId, quote.customerId(),
                issueDate == null ? LocalDate.now() : issueDate, terms == null ? "net_30" : terms, null,
                quote.memo(), lines);
        orgScope.run(orgId, () -> db.sql(
                "update ar_ap.quote set status = 'converted', invoice_id = ? where entity_id = ? and id = ?")
                .params(invoice.id(), entityId, quoteId).update());
        return invoice;
    }

    // ---------------- internals (inside OrgScope unless noted) ----------------

    private QuoteModels.Quote transition(UUID orgId, UUID entityId, UUID quoteId, String to, String message,
                                         String... from) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            QuoteModels.Quote quote = load(entityId, quoteId, entity.baseCurrency());
            requireStatus(quote, message, from);
            if (to.equals("sent") && billing.getCustomer(orgId, entityId, quote.customerId()).isArchived()) {
                throw new BusinessRuleException("CUSTOMER_ARCHIVED", "That customer is archived");
            }
            db.sql("update ar_ap.quote set status = ? where entity_id = ? and id = ?")
                    .params(to, entityId, quoteId).update();
            return load(entityId, quoteId, entity.baseCurrency());
        });
    }

    private static void requireStatus(QuoteModels.Quote quote, String message, String... allowed) {
        if (!List.of(allowed).contains(quote.status())) {
            throw new BusinessRuleException("QUOTE_WRONG_STATUS",
                    message + " — this one is " + quote.status() + ".");
        }
    }

    private void replaceLines(UUID orgId, UUID quoteId, String currency, List<NewLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A quote needs at least one line");
        }
        db.sql("delete from ar_ap.quote_line where quote_id = ?").param(quoteId).update();
        Money total = Money.zero(currency);
        int lineNo = 1;
        for (NewLine line : lines) {
            if (line.quantity().signum() <= 0 || line.quantity().scale() > 4) {
                throw new IllegalArgumentException("Quantity must be positive with at most 4 decimals");
            }
            if (!line.unitPrice().currency().equals(currency)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Prices must be in " + currency);
            }
            // Rounded once per line, the same way an invoice line is, so a converted quote totals the same.
            Money amount = line.unitPrice().multiply(line.quantity(), RoundingMode.HALF_UP);
            db.sql("""
                    insert into ar_ap.quote_line (id, org_id, quote_id, line_no, description, quantity,
                                                  unit_price_minor, amount_minor, income_account_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), orgId, quoteId, lineNo++, line.description().trim(), line.quantity(),
                            line.unitPrice().minorUnits(), amount.minorUnits(), line.incomeAccountId())
                    .update();
            total = total.add(amount);
        }
        db.sql("update ar_ap.quote set total_minor = ? where id = ?").params(total.minorUnits(), quoteId).update();
    }

    private QuoteModels.Quote load(UUID entityId, UUID quoteId, String ccy) {
        var row = db.sql("""
                select q.id, q.customer_id, c.name as customer_name, q.quote_number, q.issue_date, q.valid_until,
                       q.memo, q.total_minor, q.status, q.invoice_id, q.declined_reason
                from ar_ap.quote q join ar_ap.customer c on c.id = q.customer_id
                where q.entity_id = ? and q.id = ?""")
                .params(entityId, quoteId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quote " + quoteId + " not found"));

        List<QuoteModels.Line> lines = db.sql("""
                select id, line_no, description, quantity, unit_price_minor, amount_minor, income_account_id
                from ar_ap.quote_line where quote_id = ? order by line_no""")
                .param(quoteId)
                .query((rs, n) -> new QuoteModels.Line(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                        rs.getString("description"), rs.getBigDecimal("quantity"),
                        Money.ofMinor(rs.getLong("unit_price_minor"), ccy),
                        Money.ofMinor(rs.getLong("amount_minor"), ccy),
                        rs.getObject("income_account_id", UUID.class)))
                .list();

        LocalDate validUntil = row.get("valid_until") == null ? null
                : ((java.sql.Date) row.get("valid_until")).toLocalDate();
        String status = (String) row.get("status");
        // "Expired" is not a stored state: it is what the date says about a quote nobody has answered. Once
        // it has been accepted the deal is agreed, so the clock stops mattering.
        boolean expired = validUntil != null && validUntil.isBefore(LocalDate.now())
                && List.of("draft", "sent").contains(status);
        return new QuoteModels.Quote((UUID) row.get("id"), (UUID) row.get("customer_id"),
                (String) row.get("customer_name"), (String) row.get("quote_number"),
                ((java.sql.Date) row.get("issue_date")).toLocalDate(), validUntil, (String) row.get("memo"),
                Money.ofMinor(((Number) row.get("total_minor")).longValue(), ccy),
                expired ? "expired" : status, (UUID) row.get("invoice_id"), (String) row.get("declined_reason"),
                expired, lines);
    }

    private String nextQuoteNumber(UUID entityId) {
        Integer highest = db.sql("""
                select coalesce(max(cast(substring(quote_number from 'Q-([0-9]+)$') as int)), 0)
                from ar_ap.quote where entity_id = ? and quote_number ~ '^Q-[0-9]+$'""")
                .param(entityId).query(Integer.class).single();
        return String.format("Q-%04d", highest + 1);
    }

    private void requireNumberFree(UUID entityId, String number) {
        Boolean taken = db.sql("select exists (select 1 from ar_ap.quote where entity_id = ? and quote_number = ?)")
                .params(entityId, number).query(Boolean.class).single();
        if (taken) {
            throw new BusinessRuleException("QUOTE_NUMBER_TAKEN", "Quote number " + number + " is already used");
        }
    }
}
