package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.Patch;
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
import com.aesoftwaresolutions.solid.salestax.SalesTaxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Customers, invoices and customer payments, with the matching journal entries. */
@Service
public class BillingService {

    /** @param taxRateId optional sales-tax rate for this line; null means no tax is charged on it */
    public record NewLine(String description, BigDecimal quantity, Money unitPrice, UUID incomeAccountId,
                          UUID taxRateId) {
    }

    public record NewApplication(UUID invoiceId, Money amount) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final com.aesoftwaresolutions.solid.org.BrandingService branding;
    private final AccountService accounts;
    private final JournalService journal;
    private final SalesTaxService salesTax;
    private final AuditLog audit;

    BillingService(JdbcClient db, OrgScope orgScope, OrgService orgs,
                   com.aesoftwaresolutions.solid.org.BrandingService branding, AccountService accounts,
                   JournalService journal, SalesTaxService salesTax, AuditLog audit) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.branding = branding;
        this.accounts = accounts;
        this.journal = journal;
        this.salesTax = salesTax;
        this.audit = audit;
    }

    // ---------------- customers ----------------

    public BillingModels.Customer createCustomer(UUID orgId, UUID entityId, String name, String email, String phone,
                                                 String billingAddress, String notes) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("""
                    insert into ar_ap.customer (id, org_id, entity_id, name, email, phone, billing_address, notes)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, name.trim(), email, phone, billingAddress, notes).update();
            return findCustomer(entityId, id);
        });
    }

    public List<BillingModels.Customer> listCustomers(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(CUSTOMER_SELECT + " where entity_id = ? order by name")
                .param(entityId).query(BillingModels.Customer.class).list());
    }

    // ---------------- invoices ----------------

    public BillingModels.Invoice createInvoice(UUID orgId, UUID entityId, UUID customerId, LocalDate issueDate,
                                               String terms, String invoiceNumber, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            findCustomer(entityId, customerId);
            UUID id = Ids.newId();
            String number = invoiceNumber == null || invoiceNumber.isBlank() ? nextInvoiceNumber(entityId) : invoiceNumber.trim();
            requireNumberFree(entityId, number);
            LocalDate dueDate = dueDate(issueDate, terms);
            db.sql("""
                    insert into ar_ap.invoice (id, org_id, entity_id, customer_id, invoice_number, issue_date, due_date,
                                               terms, memo, total_minor, currency, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'draft')""")
                    .params(id, orgId, entityId, customerId, number, issueDate, dueDate, terms, memo,
                            entity.baseCurrency())
                    .update();
            replaceLines(orgId, entityId, entity.baseCurrency(), id, issueDate, lines);
            return loadInvoice(entityId, id);
        });
    }

    public BillingModels.Invoice updateDraft(UUID orgId, UUID entityId, UUID invoiceId, UUID customerId,
                                             LocalDate issueDate, String terms, String memo, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.Invoice invoice = lockInvoice(entityId, invoiceId);
            requireStatus(invoice, "draft", "INVOICE_NOT_DRAFT", "Only draft invoices can be edited");
            findCustomer(entityId, customerId);
            db.sql("update ar_ap.invoice set customer_id = ?, issue_date = ?, due_date = ?, terms = ?, memo = ? where id = ?")
                    .params(customerId, issueDate, dueDate(issueDate, terms), terms, memo, invoiceId).update();
            db.sql("delete from ar_ap.invoice_line where invoice_id = ?").param(invoiceId).update();
            replaceLines(orgId, entityId, entity.baseCurrency(), invoiceId, issueDate, lines);
            return loadInvoice(entityId, invoiceId);
        });
    }

    public List<BillingModels.Invoice> listInvoices(UUID orgId, UUID entityId, String status) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            List<UUID> ids = db.sql("""
                    select id from ar_ap.invoice
                    where entity_id = :entity and (cast(:status as text) is null or status = cast(:status as text))
                    order by issue_date desc, invoice_number desc""")
                    .param("entity", entityId).param("status", status).query(UUID.class).list();
            return ids.stream().map(id -> loadInvoice(entityId, id)).toList();
        });
    }

    /** Changes only the fields that were sent; empty means "leave it", present-with-null means "clear it". */
    public BillingModels.Customer updateCustomer(UUID orgId, UUID entityId, UUID customerId,
                                                 Patch.Field<String> name, Patch.Field<String> email,
                                                 Patch.Field<String> phone, Patch.Field<String> billingAddress,
                                                 Patch.Field<String> notes, Patch.Field<Boolean> archived) {
        orgs.getEntity(orgId, entityId);
        if (name.present() && (name.value() == null || name.value().isBlank())) {
            throw new IllegalArgumentException("A customer needs a name");
        }
        if (email.hasValue()) {
            PayableService.checkEmail(email.value());
        }

        List<String> changed = new ArrayList<>();
        BillingModels.Customer updated = orgScope.call(orgId, () -> {
            findCustomer(entityId, customerId);
            setField(changed, "name", "update ar_ap.customer set name = ? where entity_id = ? and id = ?",
                    PayableService.field(name, true), entityId, customerId);
            setField(changed, "email", "update ar_ap.customer set email = ? where entity_id = ? and id = ?",
                    PayableService.field(email, false), entityId, customerId);
            setField(changed, "phone", "update ar_ap.customer set phone = ? where entity_id = ? and id = ?",
                    PayableService.field(phone, false), entityId, customerId);
            setField(changed, "billingAddress",
                    "update ar_ap.customer set billing_address = ? where entity_id = ? and id = ?",
                    PayableService.field(billingAddress, false), entityId, customerId);
            setField(changed, "notes", "update ar_ap.customer set notes = ? where entity_id = ? and id = ?",
                    PayableService.field(notes, false), entityId, customerId);
            setField(changed, "archived", "update ar_ap.customer set is_archived = ? where entity_id = ? and id = ?",
                    PayableService.field(archived, false), entityId, customerId);
            return findCustomer(entityId, customerId);
        });
        // Field names only: an address is not something to copy into a log.
        audit.record(AuditLog.Actor.current(), orgId, "customer_updated", "customer", customerId,
                Map.of("fields", changed));
        return updated;
    }

    private void setField(List<String> changed, String field, String sql, Patch.Field<Object> value, UUID entityId,
                          UUID id) {
        if (value.present()) {
            db.sql(sql).params(value.value(), entityId, id).update();
            changed.add(field);
        }
    }

    /** One customer, for the invoice PDF and any screen that needs a name and address. */
    public BillingModels.Customer getCustomer(UUID orgId, UUID entityId, UUID customerId) {
        return listCustomers(orgId, entityId).stream().filter(c -> c.id().equals(customerId)).findFirst()
                .orElseThrow(() -> new com.aesoftwaresolutions.solid.common.NotFoundException(
                        "Customer " + customerId + " not found"));
    }

    /** The invoice rendered as a PDF the customer can be handed. */
    public byte[] invoicePdf(UUID orgId, UUID entityId, UUID invoiceId) {
        BillingModels.Invoice invoice = getInvoice(orgId, entityId, invoiceId);
        return InvoicePdf.render(orgs.getEntity(orgId, entityId), getCustomer(orgId, entityId, invoice.customerId()),
                invoice, branding.get(orgId, entityId), logoBytes(orgId, entityId));
    }

    /** The entity's logo, or nothing: every document must render without one. */
    public byte[] logoBytes(UUID orgId, UUID entityId) {
        return branding.logo(orgId, entityId).map(com.aesoftwaresolutions.solid.org.BrandingService.Logo::bytes)
                .orElse(null);
    }

    /** The letterhead, for anything in this module that draws a document. */
    public com.aesoftwaresolutions.solid.org.Branding letterhead(UUID orgId, UUID entityId) {
        return branding.get(orgId, entityId);
    }

    public BillingModels.Invoice getInvoice(UUID orgId, UUID entityId, UUID invoiceId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> loadInvoice(entityId, invoiceId));
    }

    public BillingModels.Invoice finalizeInvoice(UUID orgId, UUID entityId, UUID invoiceId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.Invoice invoice = lockInvoice(entityId, invoiceId);
            requireStatus(invoice, "draft", "INVOICE_NOT_DRAFT", "This invoice is already finalized");
            if (invoice.lines().isEmpty() || !invoice.total().isPositive()) {
                throw new BusinessRuleException("EMPTY_INVOICE", "An invoice needs at least one line and a positive total");
            }
            Account receivable = receivableAccount(orgId, entityId);
            List<JournalService.NewLine> journalLines = new ArrayList<>();
            journalLines.add(new JournalService.NewLine(receivable.id(), invoice.total(), null));
            for (BillingModels.InvoiceLine line : invoice.lines()) {
                journalLines.add(new JournalService.NewLine(line.incomeAccountId(), line.amount().negate(), line.description()));
                if (line.taxRateId() != null && line.taxAmount().isPositive()) {
                    // Sales tax is credited to its liability account, never to income: it is the state's money.
                    SalesTaxModels.Rate rate = salesTax.get(orgId, entityId, line.taxRateId());
                    journalLines.add(new JournalService.NewLine(rate.liabilityAccountId(),
                            line.taxAmount().negate(), "Sales tax " + rate.jurisdiction()));
                }
            }
            JournalEntry entry = journal.postFromSource(orgId, entityId, invoice.issueDate(),
                    "Invoice " + invoice.invoiceNumber(), journalLines, "invoice", invoiceId);
            db.sql("update ar_ap.invoice set status = 'open', journal_entry_id = ? where id = ?")
                    .params(entry.id(), invoiceId).update();
            return loadInvoice(entityId, invoiceId);
        });
    }

    public BillingModels.Invoice voidInvoice(UUID orgId, UUID entityId, UUID invoiceId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BillingModels.Invoice invoice = lockInvoice(entityId, invoiceId);
            if (invoice.status().equals("void")) {
                throw new BusinessRuleException("INVOICE_VOID", "This invoice is already void");
            }
            if (invoice.status().equals("draft")) {
                db.sql("delete from ar_ap.invoice where id = ?").param(invoiceId).update();
                return invoice;
            }
            if (invoice.amountPaid().isPositive()) {
                throw new BusinessRuleException("INVOICE_HAS_PAYMENTS",
                        "Remove the payments applied to this invoice before voiding it");
            }
            if (invoice.creditsApplied().isPositive()) {
                throw new BusinessRuleException("INVOICE_HAS_CREDITS",
                        "Unapply the credit notes pointed at this invoice before voiding it");
            }
            journal.reverse(orgId, entityId, invoice.journalEntryId(), null, "Void invoice " + invoice.invoiceNumber());
            db.sql("update ar_ap.invoice set status = 'void' where id = ?").param(invoiceId).update();
            return loadInvoice(entityId, invoiceId);
        });
    }

    // ---------------- payments ----------------

    public BillingModels.Payment recordPayment(UUID orgId, UUID entityId, UUID customerId, LocalDate receivedDate,
                                               UUID depositAccountId, String method, String reference,
                                               List<NewApplication> applications) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        if (applications.isEmpty()) {
            throw new IllegalArgumentException("A payment must be applied to at least one invoice");
        }
        return orgScope.call(orgId, () -> {
            findCustomer(entityId, customerId);
            Account deposit = accounts.get(orgId, entityId, depositAccountId);
            if (deposit.type() != AccountType.asset || deposit.isHeader() || deposit.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE", "Deposit account must be an active asset account");
            }
            Account receivable = receivableAccount(orgId, entityId);

            Money total = Money.zero(entity.baseCurrency());
            for (NewApplication application : applications) {
                if (!application.amount().isPositive()) {
                    throw new IllegalArgumentException("Applied amounts must be positive");
                }
                BillingModels.Invoice invoice = lockInvoice(entityId, application.invoiceId());
                if (!invoice.customerId().equals(customerId)) {
                    throw new BusinessRuleException("WRONG_CUSTOMER",
                            "Invoice " + invoice.invoiceNumber() + " belongs to a different customer");
                }
                if (invoice.status().equals("draft") || invoice.status().equals("void")) {
                    throw new BusinessRuleException("INVOICE_NOT_OPEN",
                            "Invoice " + invoice.invoiceNumber() + " is " + invoice.status());
                }
                if (application.amount().compareTo(invoice.balanceDue()) > 0) {
                    throw new BusinessRuleException("OVERPAYMENT",
                            "Invoice " + invoice.invoiceNumber() + " has only " + invoice.balanceDue().toDecimalString()
                                    + " " + invoice.balanceDue().currency() + " outstanding");
                }
                total = total.add(application.amount());
            }

            UUID paymentId = Ids.newId();
            JournalEntry entry = journal.postFromSource(orgId, entityId, receivedDate,
                    "Customer payment", List.of(
                            new JournalService.NewLine(deposit.id(), total, null),
                            new JournalService.NewLine(receivable.id(), total.negate(), null)),
                    "payment", paymentId);
            db.sql("""
                    insert into ar_ap.payment (id, org_id, entity_id, customer_id, received_date, amount_minor, currency,
                                               deposit_account_id, method, reference, journal_entry_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(paymentId, orgId, entityId, customerId, receivedDate, total.minorUnits(), total.currency(),
                            depositAccountId, method, reference, entry.id())
                    .update();
            for (NewApplication application : applications) {
                db.sql("insert into ar_ap.payment_application (id, org_id, payment_id, invoice_id, amount_minor) values (?, ?, ?, ?, ?)")
                        .params(Ids.newId(), orgId, paymentId, application.invoiceId(), application.amount().minorUnits())
                        .update();
                refreshInvoiceStatus(entityId, application.invoiceId());
            }
            return loadPayment(entityId, paymentId);
        });
    }

    public List<BillingModels.Payment> listPayments(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("select id from ar_ap.payment where entity_id = ? order by received_date desc, created_at desc")
                .param(entityId).query(UUID.class).list().stream().map(id -> loadPayment(entityId, id)).toList());
    }

    // ---------------- aging ----------------

    public BillingModels.AgingReport aging(UUID orgId, UUID entityId, LocalDate asOf) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        return orgScope.call(orgId, () -> {
            List<Map<String, Object>> rows = db.sql("""
                    select i.id, i.due_date, i.total_minor, c.id as customer_id, c.name as customer_name,
                           coalesce((select sum(pa.amount_minor) from ar_ap.payment_application pa
                                     join ar_ap.payment p on p.id = pa.payment_id
                                     where pa.invoice_id = i.id and p.received_date <= :asOf), 0) as paid_minor,
                           coalesce((select sum(ca.amount_minor) from ar_ap.credit_application ca
                                     join ar_ap.credit_note cn on cn.id = ca.credit_note_id
                                     where ca.invoice_id = i.id and cn.status = 'issued'
                                       and cn.issue_date <= :asOf), 0) as credited_minor
                    from ar_ap.invoice i join ar_ap.customer c on c.id = i.customer_id
                    where i.entity_id = :entity and i.status <> 'draft' and i.status <> 'void' and i.issue_date <= :asOf
                    order by c.name, i.due_date""")
                    .param("entity", entityId).param("asOf", asOf).query().listOfRows();

            Map<UUID, long[]> byCustomer = new LinkedHashMap<>();
            Map<UUID, String> names = new LinkedHashMap<>();
            long[] totals = new long[5];
            for (Map<String, Object> row : rows) {
                long open = ((Number) row.get("total_minor")).longValue()
                        - ((Number) row.get("paid_minor")).longValue()
                        - ((Number) row.get("credited_minor")).longValue();
                if (open <= 0) {
                    continue;
                }
                UUID customerId = (UUID) row.get("customer_id");
                names.put(customerId, (String) row.get("customer_name"));
                long[] buckets = byCustomer.computeIfAbsent(customerId, k -> new long[5]);
                LocalDate due = row.get("due_date") instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) row.get("due_date");
                long daysLate = java.time.temporal.ChronoUnit.DAYS.between(due, asOf);
                int bucket = daysLate <= 0 ? 0 : daysLate <= 30 ? 1 : daysLate <= 60 ? 2 : daysLate <= 90 ? 3 : 4;
                buckets[bucket] += open;
                totals[bucket] += open;
            }

            List<BillingModels.AgingBucket> customers = byCustomer.entrySet().stream()
                    .map(e -> bucket(e.getKey(), names.get(e.getKey()), e.getValue(), ccy)).toList();
            return new BillingModels.AgingReport(asOf, ccy, customers, bucket(null, "All customers", totals, ccy));
        });
    }

    // ---------------- internals (inside org scope) ----------------

    private static BillingModels.AgingBucket bucket(UUID customerId, String name, long[] values, String ccy) {
        long total = values[0] + values[1] + values[2] + values[3] + values[4];
        return new BillingModels.AgingBucket(customerId, name, Money.ofMinor(values[0], ccy), Money.ofMinor(values[1], ccy),
                Money.ofMinor(values[2], ccy), Money.ofMinor(values[3], ccy), Money.ofMinor(values[4], ccy),
                Money.ofMinor(total, ccy));
    }

    private void replaceLines(UUID orgId, UUID entityId, String currency, UUID invoiceId, LocalDate issueDate,
                              List<NewLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice needs at least one line");
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
                        "Invoice lines must use an active, non-header income account");
            }
            Money amount = line.unitPrice().multiply(line.quantity(), RoundingMode.HALF_UP);
            // Sales tax is worked out per line, on this line's amount, and kept separate from it: it is the
            // state's money, not income.
            Money lineTax = Money.zero(currency);
            if (line.taxRateId() != null) {
                lineTax = salesTax.chargeFor(orgId, entityId, line.taxRateId(), amount, issueDate).tax();
                tax = tax.add(lineTax);
            }
            total = total.add(amount);
            db.sql("""
                    insert into ar_ap.invoice_line (id, org_id, invoice_id, line_no, description, quantity,
                                                    unit_price_minor, amount_minor, income_account_id,
                                                    tax_rate_id, tax_amount_minor)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), orgId, invoiceId, lineNo++, line.description().trim(), line.quantity(),
                            line.unitPrice().minorUnits(), amount.minorUnits(), line.incomeAccountId(),
                            line.taxRateId(), lineTax.minorUnits())
                    .update();
        }
        // What the customer owes is the lines plus the tax; the tax is kept separately so the ledger and the
        // sales-tax report can tell them apart.
        db.sql("update ar_ap.invoice set total_minor = ?, tax_total_minor = ? where id = ?")
                .params(total.add(tax).minorUnits(), tax.minorUnits(), invoiceId).update();
    }

    /** The receivable account, for anything in this module that moves what a customer owes. */
    Account receivableAccount(UUID orgId, UUID entityId) {
        return accounts.list(orgId, entityId).stream()
                .filter(a -> a.type() == AccountType.asset && "ar".equals(a.subtype()) && !a.isArchived() && !a.isHeader())
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("NO_RECEIVABLE_ACCOUNT",
                        "Add an asset account with subtype 'ar' (Accounts Receivable) first"));
    }

    private static LocalDate dueDate(LocalDate issueDate, String terms) {
        return switch (terms) {
            case "due_on_receipt" -> issueDate;
            case "net_15" -> issueDate.plusDays(15);
            case "net_30" -> issueDate.plusDays(30);
            case "net_60" -> issueDate.plusDays(60);
            default -> throw new IllegalArgumentException("Unknown terms: " + terms);
        };
    }

    private String nextInvoiceNumber(UUID entityId) {
        Integer highest = db.sql("""
                select coalesce(max(cast(substring(invoice_number from 'INV-([0-9]+)$') as int)), 0)
                from ar_ap.invoice where entity_id = ? and invoice_number ~ '^INV-[0-9]+$'""")
                .param(entityId).query(Integer.class).single();
        return String.format("INV-%04d", highest + 1);
    }

    private void requireNumberFree(UUID entityId, String number) {
        Boolean taken = db.sql("select exists (select 1 from ar_ap.invoice where entity_id = ? and invoice_number = ?)")
                .params(entityId, number).query(Boolean.class).single();
        if (taken) {
            throw new BusinessRuleException("INVOICE_NUMBER_TAKEN", "Invoice number " + number + " is already used");
        }
    }

    /** Re-reads an invoice and says whether it is open, part settled or settled. */
    void refreshInvoiceStatus(UUID entityId, UUID invoiceId) {
        BillingModels.Invoice invoice = loadInvoice(entityId, invoiceId);
        String status = invoice.balanceDue().isZero() ? "paid"
                : invoice.amountPaid().isPositive() ? "partially_paid" : "open";
        db.sql("update ar_ap.invoice set status = ? where id = ?").params(status, invoiceId).update();
    }

    private static void requireStatus(BillingModels.Invoice invoice, String expected, String code, String message) {
        if (!invoice.status().equals(expected)) {
            throw new BusinessRuleException(code, message);
        }
    }

    BillingModels.Customer findCustomer(UUID entityId, UUID customerId) {
        return db.sql(CUSTOMER_SELECT + " where entity_id = ? and id = ?").params(entityId, customerId)
                .query(BillingModels.Customer.class).optional()
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
    }

    BillingModels.Invoice lockInvoice(UUID entityId, UUID invoiceId) {
        db.sql("select id from ar_ap.invoice where entity_id = ? and id = ? for update")
                .params(entityId, invoiceId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceId + " not found"));
        return loadInvoice(entityId, invoiceId);
    }

    private BillingModels.Invoice loadInvoice(UUID entityId, UUID invoiceId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, customer_id, invoice_number, issue_date, due_date, terms, memo, total_minor,
                       tax_total_minor, currency, status, journal_entry_id,
                       coalesce((select sum(amount_minor) from ar_ap.payment_application where invoice_id = ar_ap.invoice.id), 0) as paid_minor,
                       coalesce((select sum(ca.amount_minor) from ar_ap.credit_application ca
                                 join ar_ap.credit_note cn on cn.id = ca.credit_note_id
                                 where ca.invoice_id = ar_ap.invoice.id and cn.status = 'issued'), 0) as credited_minor
                from ar_ap.invoice where entity_id = ? and id = ?""")
                .params(entityId, invoiceId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceId + " not found"));
        String currency = ((String) row.get("currency")).trim();
        Money total = Money.ofMinor(((Number) row.get("total_minor")).longValue(), currency);
        Money paid = Money.ofMinor(((Number) row.get("paid_minor")).longValue(), currency);
        Money credited = Money.ofMinor(((Number) row.get("credited_minor")).longValue(), currency);
        List<BillingModels.InvoiceLine> lines = db.sql("""
                select id, line_no, description, quantity, unit_price_minor, amount_minor, income_account_id,
                       tax_rate_id, tax_amount_minor
                from ar_ap.invoice_line where invoice_id = ? order by line_no""")
                .param(invoiceId)
                .query((rs, n) -> new BillingModels.InvoiceLine(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                        rs.getString("description"), rs.getBigDecimal("quantity"),
                        Money.ofMinor(rs.getLong("unit_price_minor"), currency),
                        Money.ofMinor(rs.getLong("amount_minor"), currency),
                        rs.getObject("income_account_id", UUID.class),
                        rs.getObject("tax_rate_id", UUID.class),
                        Money.ofMinor(rs.getLong("tax_amount_minor"), currency)))
                .list();
        Money taxTotal = Money.ofMinor(((Number) row.get("tax_total_minor")).longValue(), currency);
        return new BillingModels.Invoice((UUID) row.get("id"), (UUID) row.get("entity_id"), (UUID) row.get("customer_id"),
                (String) row.get("invoice_number"), date(row.get("issue_date")), date(row.get("due_date")),
                (String) row.get("terms"), (String) row.get("memo"), total, paid,
                total.subtract(paid).subtract(credited), (String) row.get("status"),
                (UUID) row.get("journal_entry_id"), lines, taxTotal, credited);
    }

    private BillingModels.Payment loadPayment(UUID entityId, UUID paymentId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, customer_id, received_date, amount_minor, currency, deposit_account_id, method,
                       reference, journal_entry_id
                from ar_ap.payment where entity_id = ? and id = ?""")
                .params(entityId, paymentId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        String currency = ((String) row.get("currency")).trim();
        List<BillingModels.PaymentApplication> applications = db.sql("""
                select invoice_id, amount_minor from ar_ap.payment_application where payment_id = ? order by invoice_id""")
                .param(paymentId)
                .query((rs, n) -> new BillingModels.PaymentApplication(rs.getObject("invoice_id", UUID.class),
                        Money.ofMinor(rs.getLong("amount_minor"), currency)))
                .list();
        return new BillingModels.Payment((UUID) row.get("id"), (UUID) row.get("entity_id"), (UUID) row.get("customer_id"),
                date(row.get("received_date")), Money.ofMinor(((Number) row.get("amount_minor")).longValue(), currency),
                (UUID) row.get("deposit_account_id"), (String) row.get("method"), (String) row.get("reference"),
                (UUID) row.get("journal_entry_id"), applications);
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static final String CUSTOMER_SELECT = """
            select id, entity_id, name, email, phone, billing_address, notes, is_archived, created_at
            from ar_ap.customer""";
}
