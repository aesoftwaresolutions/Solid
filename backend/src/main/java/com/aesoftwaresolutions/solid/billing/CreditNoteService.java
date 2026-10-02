package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.AccountType;
import com.aesoftwaresolutions.solid.ledger.JournalEntry;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.salestax.SalesTaxModels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Credit notes: taking money off what a customer owes without pretending the invoice never happened (spec
 * 057).
 *
 * <p>Issuing posts the mirror of an invoice — the income lines are debited back and receivables credited — so
 * the customer owes less from that moment. Applying a credit to an invoice posts <em>nothing</em>: the ledger
 * has already moved, and the application only records which charge the credit offsets.
 */
@Service
public class CreditNoteService {

    /**
     * @param invoiceLineId the invoice line being credited, when this is a return rather than a goodwill
     *                      credit. It is what decides the sales tax reversed (spec 058).
     */
    public record NewLine(String description, BigDecimal quantity, Money unitPrice, UUID incomeAccountId,
                          UUID invoiceLineId) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final BillingService billing;
    private final AccountService accounts;
    private final JournalService journal;
    private final com.aesoftwaresolutions.solid.salestax.SalesTaxService salesTax;

    CreditNoteService(JdbcClient db, OrgScope orgScope, OrgService orgs, BillingService billing,
                      AccountService accounts, JournalService journal,
                      com.aesoftwaresolutions.solid.salestax.SalesTaxService salesTax) {
        this.salesTax = salesTax;
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.billing = billing;
        this.accounts = accounts;
        this.journal = journal;
    }

    public BillingModels.CreditNote create(UUID orgId, UUID entityId, UUID customerId, LocalDate issueDate,
                                           String creditNumber, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            billing.findCustomer(entityId, customerId);
            UUID id = Ids.newId();
            String number = creditNumber == null || creditNumber.isBlank() ? nextNumber(entityId)
                    : creditNumber.trim();
            requireNumberFree(entityId, number);
            db.sql("""
                    insert into ar_ap.credit_note (id, org_id, entity_id, customer_id, credit_number, issue_date,
                                                   memo, total_minor, currency, status)
                    values (?, ?, ?, ?, ?, ?, ?, 0, ?, 'draft')""")
                    .params(id, orgId, entityId, customerId, number, issueDate, memo, entity.baseCurrency())
                    .update();
            replaceLines(orgId, entityId, entity.baseCurrency(), id, lines);
            return load(entityId, id);
        });
    }

    public BillingModels.CreditNote update(UUID orgId, UUID entityId, UUID creditNoteId, UUID customerId,
                                           LocalDate issueDate, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.CreditNote credit = lock(entityId, creditNoteId);
            requireStatus(credit, "draft", "Only a draft credit note can be edited");
            billing.findCustomer(entityId, customerId);
            db.sql("update ar_ap.credit_note set customer_id = ?, issue_date = ?, memo = ? where id = ?")
                    .params(customerId, issueDate, memo, creditNoteId).update();
            db.sql("delete from ar_ap.credit_note_line where credit_note_id = ?").param(creditNoteId).update();
            replaceLines(orgId, entityId, entity.baseCurrency(), creditNoteId, lines);
            return load(entityId, creditNoteId);
        });
    }

    public List<BillingModels.CreditNote> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("""
                        select id from ar_ap.credit_note where entity_id = ?
                        order by issue_date desc, credit_number desc""")
                .param(entityId).query(UUID.class).list().stream().map(id -> load(entityId, id)).toList());
    }

    public BillingModels.CreditNote get(UUID orgId, UUID entityId, UUID creditNoteId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, creditNoteId));
    }

    /** Puts the credit in the books: the income lines back out, receivables down by the total. */
    public BillingModels.CreditNote issue(UUID orgId, UUID entityId, UUID creditNoteId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.CreditNote credit = lock(entityId, creditNoteId);
            requireStatus(credit, "draft", "This credit note has already been issued");
            if (credit.lines().isEmpty() || !credit.total().isPositive()) {
                throw new BusinessRuleException("EMPTY_CREDIT_NOTE",
                        "A credit note needs at least one line and a positive total");
            }
            // The invoices this note credits must still be open now, not just when the note was drafted: a
            // credit against a voided invoice would undo a sale that was already undone (spec 065, row 1).
            List<String> closed = db.sql("""
                    select distinct i.invoice_number from ar_ap.credit_note_line cl
                    join ar_ap.invoice_line il on il.id = cl.invoice_line_id
                    join ar_ap.invoice i on i.id = il.invoice_id
                    where cl.credit_note_id = ? and i.status in ('void', 'draft')
                    order by 1""").param(creditNoteId).query(String.class).list();
            if (!closed.isEmpty()) {
                throw new BusinessRuleException("INVOICE_NOT_OPEN",
                        "This credit note credits " + String.join(", ", closed) + ", which is no longer open");
            }
            Account receivable = billing.receivableAccount(orgId, entityId);
            List<JournalService.NewLine> journalLines = new ArrayList<>();
            for (BillingModels.CreditNoteLine line : credit.lines()) {
                // Taking back a charge takes it back from the business line it was earned in (spec 069). A credit
                // with no invoice line behind it is unassigned.
                UUID businessLineId = line.invoiceLineId() == null ? null : db.sql("""
                        select i.business_line_id from ar_ap.invoice_line il join ar_ap.invoice i on i.id = il.invoice_id
                        where il.id = ?""").param(line.invoiceLineId()).query(UUID.class).optional().orElse(null);
                journalLines.add(new JournalService.NewLine(line.incomeAccountId(), line.amount(),
                        line.description(), businessLineId));
                if (line.taxRateId() != null && line.taxAmount().isPositive()) {
                    // The tax goes back out of the liability account it was credited to: the entity owes the
                    // state less by exactly what it is reversing.
                    SalesTaxModels.Rate rate = salesTax.get(orgId, entityId, line.taxRateId());
                    journalLines.add(new JournalService.NewLine(rate.liabilityAccountId(), line.taxAmount(),
                            "Sales tax reversed " + rate.jurisdiction()));
                }
            }
            journalLines.add(new JournalService.NewLine(receivable.id(), credit.total().negate(), null));
            JournalEntry entry = journal.postTakingBackFromSource(orgId, entityId, credit.issueDate(),
                    "Credit note " + credit.creditNumber(), journalLines, "credit_note", creditNoteId);
            db.sql("update ar_ap.credit_note set status = 'issued', journal_entry_id = ? where id = ?")
                    .params(entry.id(), creditNoteId).update();
            return load(entityId, creditNoteId);
        });
    }

    /**
     * Points part of the credit at one invoice. No entry is posted — issuing already moved the money — so this
     * only changes what the invoice reads as outstanding.
     */
    public BillingModels.CreditNote apply(UUID orgId, UUID entityId, UUID creditNoteId, UUID invoiceId,
                                          Money amount) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.CreditNote credit = lock(entityId, creditNoteId);
            requireStatus(credit, "issued", "Only an issued credit note can be applied");
            if (amount == null || !amount.isPositive()) {
                throw new IllegalArgumentException("An applied amount must be positive");
            }
            if (!amount.currency().equals(credit.total().currency())) {
                throw new BusinessRuleException("CURRENCY_MISMATCH",
                        "A credit must be applied in " + credit.total().currency());
            }
            BillingModels.Invoice invoice = billing.lockInvoice(entityId, invoiceId);
            if (!invoice.customerId().equals(credit.customerId())) {
                throw new BusinessRuleException("WRONG_CUSTOMER",
                        "Invoice " + invoice.invoiceNumber() + " belongs to a different customer");
            }
            if (invoice.status().equals("draft") || invoice.status().equals("void")) {
                throw new BusinessRuleException("INVOICE_NOT_OPEN",
                        "Invoice " + invoice.invoiceNumber() + " is " + invoice.status());
            }
            if (amount.compareTo(credit.remaining()) > 0) {
                throw new BusinessRuleException("OVER_APPLIED", "Credit note " + credit.creditNumber()
                        + " has only " + credit.remaining().toDecimalString() + " "
                        + credit.remaining().currency() + " left");
            }
            if (amount.compareTo(invoice.balanceDue()) > 0) {
                throw new BusinessRuleException("OVER_APPLIED", "Invoice " + invoice.invoiceNumber()
                        + " has only " + invoice.balanceDue().toDecimalString() + " "
                        + invoice.balanceDue().currency() + " outstanding");
            }
            db.sql("""
                    insert into ar_ap.credit_application (id, org_id, credit_note_id, invoice_id, amount_minor)
                    values (?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), orgId, creditNoteId, invoiceId, amount.minorUnits())
                    .update();
            billing.refreshInvoiceStatus(entityId, invoiceId);
            return load(entityId, creditNoteId);
        });
    }

    /** Takes the credit back off an invoice, leaving it unspent. */
    public BillingModels.CreditNote unapply(UUID orgId, UUID entityId, UUID creditNoteId, UUID applicationId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            lock(entityId, creditNoteId);
            UUID invoiceId = db.sql("""
                            select invoice_id from ar_ap.credit_application
                            where id = ? and credit_note_id = ?""")
                    .params(applicationId, creditNoteId).query(UUID.class).optional()
                    .orElseThrow(() -> new NotFoundException("Credit application " + applicationId + " not found"));
            db.sql("delete from ar_ap.credit_application where id = ?").param(applicationId).update();
            billing.refreshInvoiceStatus(entityId, invoiceId);
            return load(entityId, creditNoteId);
        });
    }

    /** Undoes an issued credit with a reversing entry. A credit still pointed at an invoice cannot be voided. */
    public BillingModels.CreditNote voidCredit(UUID orgId, UUID entityId, UUID creditNoteId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.CreditNote credit = lock(entityId, creditNoteId);
            if (credit.status().equals("void")) {
                throw new BusinessRuleException("CREDIT_NOTE_VOID", "This credit note is already void");
            }
            if (credit.status().equals("draft")) {
                db.sql("delete from ar_ap.credit_note where id = ?").param(creditNoteId).update();
                return credit;
            }
            if (credit.applied().isPositive()) {
                throw new BusinessRuleException("CREDIT_NOTE_APPLIED",
                        "Unapply this credit note from its invoices before voiding it");
            }
            journal.reverse(orgId, entityId, credit.journalEntryId(), null,
                    "Void credit note " + credit.creditNumber());
            db.sql("update ar_ap.credit_note set status = 'void' where id = ?").param(creditNoteId).update();
            return load(entityId, creditNoteId);
        });
    }

    // ---------------- internals ----------------

    private void replaceLines(UUID orgId, UUID entityId, String currency, UUID creditNoteId, List<NewLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A credit note needs at least one line");
        }
        Money total = Money.zero(currency);
        Money tax = Money.zero(currency);
        int lineNo = 1;
        for (NewLine line : lines) {
            if (line.quantity().signum() <= 0 || line.quantity().scale() > 4) {
                throw new IllegalArgumentException("Quantity must be positive with at most 4 decimals");
            }
            if (!line.unitPrice().currency().equals(currency)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Unit prices must be in " + currency);
            }
            if (line.unitPrice().isNegative()) {
                throw new IllegalArgumentException("Unit price can't be negative");
            }
            Account income = accounts.get(orgId, entityId, line.incomeAccountId());
            if (income.type() != AccountType.income || income.isHeader() || income.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                        "Credit note lines must use an active, non-header income account");
            }
            Money amount = line.unitPrice().multiply(line.quantity(), RoundingMode.HALF_UP);
            Origin origin = line.invoiceLineId() == null ? null
                    : origin(entityId, creditNoteId, line.invoiceLineId(), amount, currency);
            Money lineTax = origin == null ? Money.zero(currency) : origin.tax();
            db.sql("""
                    insert into ar_ap.credit_note_line (id, org_id, credit_note_id, line_no, description, quantity,
                                                        unit_price_minor, amount_minor, income_account_id,
                                                        invoice_line_id, tax_rate_id, tax_amount_minor)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), orgId, creditNoteId, lineNo++, line.description().trim(), line.quantity(),
                            line.unitPrice().minorUnits(), amount.minorUnits(), line.incomeAccountId(),
                            line.invoiceLineId(), origin == null ? null : origin.rateId(), lineTax.minorUnits())
                    .update();
            total = total.add(amount).add(lineTax);
            tax = tax.add(lineTax);
        }
        db.sql("update ar_ap.credit_note set total_minor = ?, tax_total_minor = ? where id = ?")
                .params(total.minorUnits(), tax.minorUnits(), creditNoteId).update();
    }

    /** What an invoice line says about the tax a credit against it reverses. */
    private record Origin(UUID rateId, Money tax) {
    }

    /**
     * Works out the sales tax a credit line reverses, following the CPA ruling recorded in
     * docs/tax-sources/credit-note-sales-tax.md: a full credit gives back the exact tax charged, a partial one
     * gives back the credited amount at the original rate.
     */
    private Origin origin(UUID entityId, UUID creditNoteId, UUID invoiceLineId, Money creditedAmount,
                          String currency) {
        Map<String, Object> row = db.sql("""
                        select l.amount_minor, l.tax_rate_id, l.tax_amount_minor, i.id as invoice_id,
                               i.status, i.customer_id,
                               coalesce(sum(cl.amount_minor), 0) as already_credited_minor,
                               coalesce(sum(cl.tax_amount_minor), 0) as already_reversed_tax_minor
                        from ar_ap.invoice_line l join ar_ap.invoice i on i.id = l.invoice_id
                        left join ar_ap.credit_note_line cl on cl.invoice_line_id = l.id
                             and cl.credit_note_id <> ?
                             and exists (select 1 from ar_ap.credit_note cn
                                         where cn.id = cl.credit_note_id and cn.status <> 'void')
                        where l.id = ? and i.entity_id = ?
                        group by l.id, i.id""")
                .params(creditNoteId, invoiceLineId, entityId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice line " + invoiceLineId + " not found"));

        String status = (String) row.get("status");
        if (status.equals("draft") || status.equals("void")) {
            throw new BusinessRuleException("INVOICE_NOT_OPEN",
                    "The invoice this line belongs to is " + status);
        }
        UUID customerId = db.sql("select customer_id from ar_ap.credit_note where id = ?")
                .param(creditNoteId).query(UUID.class).single();
        if (!customerId.equals(row.get("customer_id"))) {
            throw new BusinessRuleException("WRONG_CUSTOMER",
                    "That invoice line belongs to a different customer");
        }

        Money originalAmount = Money.ofMinor(((Number) row.get("amount_minor")).longValue(), currency);
        Money alreadyCredited = Money.ofMinor(((Number) row.get("already_credited_minor")).longValue(), currency);
        if (alreadyCredited.add(creditedAmount).compareTo(originalAmount) > 0) {
            throw new BusinessRuleException("OVER_CREDITED", "That line was charged "
                    + originalAmount.toDecimalString() + " " + currency + " and "
                    + alreadyCredited.toDecimalString() + " of it is already credited");
        }

        UUID rateId = (UUID) row.get("tax_rate_id");
        if (rateId == null) {
            return new Origin(null, Money.zero(currency));
        }
        Money originalTax = Money.ofMinor(((Number) row.get("tax_amount_minor")).longValue(), currency);
        Money alreadyReversed = Money.ofMinor(((Number) row.get("already_reversed_tax_minor")).longValue(),
                currency);
        Money taxLeft = originalTax.subtract(alreadyReversed);
        if (taxLeft.isNegative()) {
            // Only possible for notes issued before this rule existed; there is nothing left to give back.
            taxLeft = Money.zero(currency);
        }
        // Full credit — in one note or finished off by this one — gives back exactly what is left of the tax
        // charged. Each partial note rounds on its own, so without this a line credited in halves can end a cent
        // short or a cent over, and the CPA's ruling is that a fully credited sale owes no tax at all
        // (spec 065, row 3).
        if (alreadyCredited.add(creditedAmount).compareTo(originalAmount) == 0) {
            return new Origin(rateId, taxLeft);
        }
        // Partial credit: the credited amount at the rate the invoice used, whatever its state today — but never
        // more than is still left, however the rounding of earlier partial notes happened to fall.
        Money partial = salesTax.reversalAtStoredRate(entityId, rateId, creditedAmount).tax();
        return new Origin(rateId, partial.compareTo(taxLeft) > 0 ? taxLeft : partial);
    }

    private BillingModels.CreditNote lock(UUID entityId, UUID creditNoteId) {
        db.sql("select id from ar_ap.credit_note where entity_id = ? and id = ? for update")
                .params(entityId, creditNoteId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Credit note " + creditNoteId + " not found"));
        return load(entityId, creditNoteId);
    }

    private BillingModels.CreditNote load(UUID entityId, UUID creditNoteId) {
        Map<String, Object> row = db.sql("""
                        select cn.id, cn.customer_id, c.name as customer_name, cn.credit_number, cn.issue_date,
                               cn.memo, cn.total_minor, cn.currency, cn.status, cn.journal_entry_id,
                               cn.tax_total_minor,
                               coalesce((select sum(amount_minor) from ar_ap.credit_application
                                         where credit_note_id = cn.id), 0) as applied_minor
                        from ar_ap.credit_note cn join ar_ap.customer c on c.id = cn.customer_id
                        where cn.entity_id = ? and cn.id = ?""")
                .params(entityId, creditNoteId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Credit note " + creditNoteId + " not found"));
        String currency = ((String) row.get("currency")).trim();
        Money total = Money.ofMinor(((Number) row.get("total_minor")).longValue(), currency);
        Money applied = Money.ofMinor(((Number) row.get("applied_minor")).longValue(), currency);

        List<BillingModels.CreditNoteLine> lines = db.sql("""
                        select id, line_no, description, quantity, unit_price_minor, amount_minor,
                               income_account_id, invoice_line_id, tax_rate_id, tax_amount_minor
                        from ar_ap.credit_note_line where credit_note_id = ? order by line_no""")
                .param(creditNoteId)
                .query((rs, n) -> new BillingModels.CreditNoteLine(rs.getObject("id", UUID.class),
                        rs.getInt("line_no"), rs.getString("description"), rs.getBigDecimal("quantity"),
                        Money.ofMinor(rs.getLong("unit_price_minor"), currency),
                        Money.ofMinor(rs.getLong("amount_minor"), currency),
                        rs.getObject("income_account_id", UUID.class),
                        rs.getObject("invoice_line_id", UUID.class),
                        rs.getObject("tax_rate_id", UUID.class),
                        Money.ofMinor(rs.getLong("tax_amount_minor"), currency)))
                .list();
        List<BillingModels.CreditApplication> applications = db.sql("""
                        select ca.id, ca.invoice_id, i.invoice_number, ca.amount_minor
                        from ar_ap.credit_application ca join ar_ap.invoice i on i.id = ca.invoice_id
                        where ca.credit_note_id = ? order by ca.created_at""")
                .param(creditNoteId)
                .query((rs, n) -> new BillingModels.CreditApplication(rs.getObject("id", UUID.class),
                        rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                        Money.ofMinor(rs.getLong("amount_minor"), currency)))
                .list();

        return new BillingModels.CreditNote((UUID) row.get("id"), (UUID) row.get("customer_id"),
                (String) row.get("customer_name"), (String) row.get("credit_number"), date(row.get("issue_date")),
                (String) row.get("memo"), total, (String) row.get("status"), (UUID) row.get("journal_entry_id"),
                applied, total.subtract(applied), lines, applications,
                Money.ofMinor(((Number) row.get("tax_total_minor")).longValue(), currency));
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static void requireStatus(BillingModels.CreditNote credit, String expected, String message) {
        if (!credit.status().equals(expected)) {
            throw new BusinessRuleException("CREDIT_NOTE_WRONG_STATUS",
                    message + " — this one is " + credit.status());
        }
    }

    private String nextNumber(UUID entityId) {
        Integer highest = db.sql("""
                        select coalesce(max(cast(substring(credit_number from 'CN-([0-9]+)$') as int)), 0)
                        from ar_ap.credit_note where entity_id = ? and credit_number ~ '^CN-[0-9]+$'""")
                .param(entityId).query(Integer.class).single();
        return String.format("CN-%04d", highest + 1);
    }

    private void requireNumberFree(UUID entityId, String number) {
        Boolean taken = db.sql("select exists (select 1 from ar_ap.credit_note where entity_id = ? and credit_number = ?)")
                .params(entityId, number).query(Boolean.class).single();
        if (taken) {
            throw new BusinessRuleException("CREDIT_NUMBER_TAKEN",
                    "Credit note number " + number + " is already used");
        }
    }
}
