package com.aesoftwaresolutions.solid.salestax;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The sales-tax rates a business has entered, the tax on an invoice line, and what has been collected.
 *
 * <p>Solid supplies no rates of its own — see the module docs. Tax is worked out per line on integer minor
 * units, half-up, because that is what a state expects and what a customer can check against their own maths.
 */
@Service
public class SalesTaxService {

    static final String REPORT_NOTE = "Tax collected is money you are holding for the state, not income. These "
            + "figures come from invoices you issued in the period (accrual). Your state may want a cash-basis "
            + "figure, and the rates here are the ones you entered — checking them, and filing, is yours to do. "
            + "Credit notes are treated as an ongoing adjustment: a reversal falls in the period the credit was "
            + "issued, and no closed period is ever reopened. Some states instead want the original period's "
            + "return amended; the prior-period adjustments below are exactly those credits, so you and your "
            + "accountant can decide which your state requires.";

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;

    SalesTaxService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
    }

    public SalesTaxModels.Rate create(UUID orgId, UUID entityId, String jurisdiction, BigDecimal ratePercent,
                                      UUID liabilityAccountId, LocalDate effectiveFrom, LocalDate effectiveTo,
                                      String note) {
        orgs.getEntity(orgId, entityId);
        if (ratePercent == null || ratePercent.signum() < 0 || ratePercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("A rate must be between 0 and 100 percent");
        }
        if (ratePercent.scale() > 4) {
            throw new IllegalArgumentException("A rate can have at most 4 decimal places");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
        Account liability = accounts.get(orgId, entityId, liabilityAccountId);
        if (liability.type() != AccountType.liability || liability.isHeader() || liability.isArchived()) {
            throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                    "Sales tax collected is a liability: choose an active, non-header liability account "
                            + "(for example \"Sales Tax Payable\").");
        }

        UUID id = Ids.newId();
        orgScope.run(orgId, () -> db.sql("""
                insert into stx.tax_rate (id, org_id, entity_id, jurisdiction, rate_percent, liability_account_id,
                                          effective_from, effective_to, note)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, orgId, entityId, jurisdiction.trim(), ratePercent, liabilityAccountId, effectiveFrom,
                        effectiveTo, note)
                .update());
        return get(orgId, entityId, id);
    }

    public List<SalesTaxModels.Rate> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(SELECT + " where entity_id = ? order by jurisdiction, effective_from")
                .param(entityId).query(SalesTaxService::mapRate).list());
    }

    public SalesTaxModels.Rate get(UUID orgId, UUID entityId, UUID rateId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(SELECT + " where entity_id = ? and id = ?")
                .params(entityId, rateId).query(SalesTaxService::mapRate).optional()
                .orElseThrow(() -> new NotFoundException("Sales tax rate " + rateId + " not found")));
    }

    /** Rates are deactivated, never deleted: an invoice must keep showing the rate it actually charged. */
    public SalesTaxModels.Rate deactivate(UUID orgId, UUID entityId, UUID rateId) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> {
            int updated = db.sql("update stx.tax_rate set is_active = false where entity_id = ? and id = ?")
                    .params(entityId, rateId).update();
            if (updated == 0) {
                throw new NotFoundException("Sales tax rate " + rateId + " not found");
            }
        });
        return get(orgId, entityId, rateId);
    }

    /**
     * The tax on one line, and the account it is owed to.
     *
     * <p>Must be called inside an {@link OrgScope}; the billing module calls it while writing invoice lines.
     */
    public Charge chargeFor(UUID orgId, UUID entityId, UUID rateId, Money lineAmount, LocalDate issueDate) {
        SalesTaxModels.Rate rate = db.sql(SELECT + " where entity_id = ? and id = ?")
                .params(entityId, rateId).query(SalesTaxService::mapRate).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sales tax rate " + rateId + " is not a rate of this entity"));
        if (!rate.active()) {
            throw new BusinessRuleException("RATE_INACTIVE",
                    "The rate for " + rate.jurisdiction() + " is no longer in use; pick a current one.");
        }
        if (issueDate.isBefore(rate.effectiveFrom())
                || (rate.effectiveTo() != null && issueDate.isAfter(rate.effectiveTo()))) {
            throw new BusinessRuleException("RATE_NOT_EFFECTIVE",
                    "The rate for " + rate.jurisdiction() + " does not apply on " + issueDate + ".");
        }
        // Integer minor units, half-up, per line — never a float and never on the invoice total.
        BigDecimal tax = BigDecimal.valueOf(lineAmount.minorUnits())
                .multiply(rate.ratePercent())
                .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
        return new Charge(rate, Money.ofMinor(tax.longValueExact(), lineAmount.currency()));
    }

    /**
     * The tax on an amount at a rate <em>already used</em>, for reversing a charge rather than making one.
     *
     * <p>Deliberately skips the "is this rate effective today" checks {@link #chargeFor} makes: undoing tax
     * charged under last year's rate is governed by last year's rate, whether or not it is still in use. See
     * docs/tax-sources/credit-note-sales-tax.md.
     */
    public Charge reversalAtStoredRate(UUID entityId, UUID rateId, Money amount) {
        SalesTaxModels.Rate rate = db.sql(SELECT + " where entity_id = ? and id = ?")
                .params(entityId, rateId).query(SalesTaxService::mapRate).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sales tax rate " + rateId + " is not a rate of this entity"));
        BigDecimal tax = BigDecimal.valueOf(amount.minorUnits())
                .multiply(rate.ratePercent())
                .divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP);
        return new Charge(rate, Money.ofMinor(tax.longValueExact(), amount.currency()));
    }

    public record Charge(SalesTaxModels.Rate rate, Money tax) {
    }

    public SalesTaxModels.SalesTaxReport report(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }

        return orgScope.call(orgId, () -> {
            // Invoices charge the tax; issued credit notes take it back out, in the period they were issued
            // (spec 058). Both sides are summed here so the report is what is actually owed to the state.
            List<Map<String, Object>> rows = db.sql("""
                    select jurisdiction, rate_percent,
                           sum(taxable_charged_minor) as taxable_charged_minor,
                           sum(tax_charged_minor) as tax_charged_minor,
                           sum(taxable_credited_minor) as taxable_credited_minor,
                           sum(tax_credited_minor) as tax_credited_minor
                    from (
                        select r.jurisdiction, r.rate_percent,
                               sum(l.amount_minor) as taxable_charged_minor,
                               sum(l.tax_amount_minor) as tax_charged_minor,
                               0 as taxable_credited_minor, 0 as tax_credited_minor
                        from ar_ap.invoice_line l
                        join ar_ap.invoice i on i.id = l.invoice_id
                        join stx.tax_rate r on r.id = l.tax_rate_id
                        where i.entity_id = :entity and i.status in ('open', 'partially_paid', 'paid')
                          and i.issue_date between :from and :to
                        group by r.jurisdiction, r.rate_percent
                        union all
                        select r.jurisdiction, r.rate_percent,
                               0 as taxable_charged_minor, 0 as tax_charged_minor,
                               sum(cl.amount_minor) as taxable_credited_minor,
                               sum(cl.tax_amount_minor) as tax_credited_minor
                        from ar_ap.credit_note_line cl
                        join ar_ap.credit_note cn on cn.id = cl.credit_note_id
                        join stx.tax_rate r on r.id = cl.tax_rate_id
                        where cn.entity_id = :entity and cn.status = 'issued'
                          and cn.issue_date between :from and :to
                        group by r.jurisdiction, r.rate_percent
                    ) both_sides
                    group by jurisdiction, rate_percent
                    order by jurisdiction, rate_percent""")
                    .param("entity", entityId).param("from", from).param("to", to).query().listOfRows();

            List<SalesTaxModels.JurisdictionTotal> totals = new ArrayList<>();
            Money taxable = Money.zero(ccy);
            Money collected = Money.zero(ccy);
            for (Map<String, Object> row : rows) {
                Money charged = Money.ofMinor(((Number) row.get("taxable_charged_minor")).longValue(), ccy);
                Money chargedTax = Money.ofMinor(((Number) row.get("tax_charged_minor")).longValue(), ccy);
                Money credited = Money.ofMinor(((Number) row.get("taxable_credited_minor")).longValue(), ccy);
                Money creditedTax = Money.ofMinor(((Number) row.get("tax_credited_minor")).longValue(), ccy);
                Money rowTaxable = charged.subtract(credited);
                Money rowTax = chargedTax.subtract(creditedTax);
                totals.add(new SalesTaxModels.JurisdictionTotal((String) row.get("jurisdiction"),
                        (BigDecimal) row.get("rate_percent"), rowTaxable, rowTax, charged, chargedTax,
                        credited, creditedTax));
                taxable = taxable.add(rowTaxable);
                collected = collected.add(rowTax);
            }
            return new SalesTaxModels.SalesTaxReport(from, to, ccy, List.copyOf(totals), taxable, collected,
                    priorPeriodAdjustments(entityId, from, to, ccy), REPORT_NOTE);
        });
    }

    /**
     * Credits issued inside the period against invoices from before it.
     *
     * <p>Their tax counts in this period's figures, which is what an ongoing-adjustment system does. They are
     * listed separately because a state that takes the amended-return view needs to know precisely which
     * earlier returns are affected (spec 059).
     */
    private List<SalesTaxModels.PriorPeriodAdjustment> priorPeriodAdjustments(UUID entityId, LocalDate from,
                                                                              LocalDate to, String ccy) {
        return db.sql("""
                        select cn.credit_number, cn.issue_date as credit_date, i.invoice_number,
                               i.issue_date as invoice_date, r.jurisdiction,
                               cl.amount_minor, cl.tax_amount_minor
                        from ar_ap.credit_note_line cl
                        join ar_ap.credit_note cn on cn.id = cl.credit_note_id
                        join ar_ap.invoice_line il on il.id = cl.invoice_line_id
                        join ar_ap.invoice i on i.id = il.invoice_id
                        join stx.tax_rate r on r.id = cl.tax_rate_id
                        where cn.entity_id = :entity and cn.status = 'issued'
                          and cn.issue_date between :from and :to
                          and i.issue_date < :from
                        order by cn.issue_date, cn.credit_number""")
                .param("entity", entityId).param("from", from).param("to", to)
                .query((rs, n) -> new SalesTaxModels.PriorPeriodAdjustment(rs.getString("credit_number"),
                        rs.getObject("credit_date", LocalDate.class), rs.getString("invoice_number"),
                        rs.getObject("invoice_date", LocalDate.class), rs.getString("jurisdiction"),
                        Money.ofMinor(rs.getLong("amount_minor"), ccy),
                        Money.ofMinor(rs.getLong("tax_amount_minor"), ccy)))
                .list();
    }

    private static final String SELECT = """
            select id, entity_id, jurisdiction, rate_percent, liability_account_id, effective_from, effective_to,
                   note, is_active
            from stx.tax_rate""";

    private static SalesTaxModels.Rate mapRate(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SalesTaxModels.Rate(rs.getObject("id", UUID.class), rs.getObject("entity_id", UUID.class),
                rs.getString("jurisdiction"), rs.getBigDecimal("rate_percent"),
                rs.getObject("liability_account_id", UUID.class),
                rs.getObject("effective_from", LocalDate.class),
                Optional.ofNullable(rs.getObject("effective_to", LocalDate.class)).orElse(null),
                rs.getString("note"), rs.getBoolean("is_active"));
    }
}
