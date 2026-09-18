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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Vendors, bills (accounts payable), bill payments and 1099-NEC candidates. */
@Service
public class PayableService {

    public record NewBillLine(String description, Money amount, UUID expenseAccountId) {
    }

    public record NewApplication(UUID billId, Money amount) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;
    private final Form1099Thresholds thresholds;

    PayableService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts, JournalService journal,
                   Form1099Thresholds thresholds) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
        this.thresholds = thresholds;
    }

    // ---------------- vendors ----------------

    public PayableModels.Vendor createVendor(UUID orgId, UUID entityId, String name, String email, String phone,
                                             String address, String taxIdLast4, String taxClassification,
                                             boolean is1099Vendor, UUID defaultExpenseAccountId) {
        orgs.getEntity(orgId, entityId);
        if (defaultExpenseAccountId != null) {
            accounts.get(orgId, entityId, defaultExpenseAccountId);
        }
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("""
                    insert into ar_ap.vendor (id, org_id, entity_id, name, email, phone, address, tax_id_last4,
                                              tax_classification, is_1099_vendor, default_expense_account_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, name.trim(), email, phone, address, taxIdLast4, taxClassification,
                            is1099Vendor, defaultExpenseAccountId)
                    .update();
            return findVendor(entityId, id);
        });
    }

    public List<PayableModels.Vendor> listVendors(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(VENDOR_SELECT + " where entity_id = ? order by name")
                .param(entityId).query(PayableModels.Vendor.class).list());
    }

    // ---------------- bills ----------------

    public PayableModels.Bill createBill(UUID orgId, UUID entityId, UUID vendorId, LocalDate billDate, String terms,
                                         String vendorReference, String memo, List<NewBillLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            findVendor(entityId, vendorId);
            UUID id = Ids.newId();
            db.sql("""
                    insert into ar_ap.bill (id, org_id, entity_id, vendor_id, vendor_reference, bill_date, due_date,
                                            terms, memo, total_minor, currency, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'draft')""")
                    .params(id, orgId, entityId, vendorId, vendorReference, billDate, dueDate(billDate, terms), terms,
                            memo, entity.baseCurrency())
                    .update();
            replaceLines(orgId, entityId, entity.baseCurrency(), id, lines);
            return loadBill(entityId, id);
        });
    }

    public PayableModels.Bill updateDraft(UUID orgId, UUID entityId, UUID billId, UUID vendorId, LocalDate billDate,
                                          String terms, String vendorReference, String memo, List<NewBillLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            PayableModels.Bill bill = lockBill(entityId, billId);
            if (!bill.status().equals("draft")) {
                throw new BusinessRuleException("BILL_NOT_DRAFT", "Only draft bills can be edited");
            }
            findVendor(entityId, vendorId);
            db.sql("update ar_ap.bill set vendor_id = ?, bill_date = ?, due_date = ?, terms = ?, vendor_reference = ?, memo = ? where id = ?")
                    .params(vendorId, billDate, dueDate(billDate, terms), terms, vendorReference, memo, billId).update();
            db.sql("delete from ar_ap.bill_line where bill_id = ?").param(billId).update();
            replaceLines(orgId, entityId, entity.baseCurrency(), billId, lines);
            return loadBill(entityId, billId);
        });
    }

    public List<PayableModels.Bill> listBills(UUID orgId, UUID entityId, String status) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("""
                select id from ar_ap.bill
                where entity_id = :entity and (cast(:status as text) is null or status = cast(:status as text))
                order by bill_date desc, created_at desc""")
                .param("entity", entityId).param("status", status).query(UUID.class).list().stream()
                .map(id -> loadBill(entityId, id)).toList());
    }

    public PayableModels.Bill getBill(UUID orgId, UUID entityId, UUID billId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> loadBill(entityId, billId));
    }

    public PayableModels.Bill approve(UUID orgId, UUID entityId, UUID billId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            PayableModels.Bill bill = lockBill(entityId, billId);
            if (!bill.status().equals("draft")) {
                throw new BusinessRuleException("BILL_NOT_DRAFT", "This bill is already approved");
            }
            if (bill.lines().isEmpty() || !bill.total().isPositive()) {
                throw new BusinessRuleException("EMPTY_BILL", "A bill needs at least one line and a positive total");
            }
            Account payable = payableAccount(orgId, entityId);
            List<JournalService.NewLine> journalLines = new ArrayList<>();
            for (PayableModels.BillLine line : bill.lines()) {
                journalLines.add(new JournalService.NewLine(line.expenseAccountId(), line.amount(), line.description()));
            }
            journalLines.add(new JournalService.NewLine(payable.id(), bill.total().negate(), null));
            JournalEntry entry = journal.postFromSource(orgId, entityId, bill.billDate(),
                    "Bill" + (bill.vendorReference() == null ? "" : " " + bill.vendorReference()), journalLines,
                    "bill", billId);
            db.sql("update ar_ap.bill set status = 'open', journal_entry_id = ? where id = ?")
                    .params(entry.id(), billId).update();
            return loadBill(entityId, billId);
        });
    }

    public PayableModels.Bill voidBill(UUID orgId, UUID entityId, UUID billId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            PayableModels.Bill bill = lockBill(entityId, billId);
            if (bill.status().equals("void")) {
                throw new BusinessRuleException("BILL_VOID", "This bill is already void");
            }
            if (bill.status().equals("draft")) {
                db.sql("delete from ar_ap.bill where id = ?").param(billId).update();
                return bill;
            }
            if (bill.amountPaid().isPositive()) {
                throw new BusinessRuleException("BILL_HAS_PAYMENTS", "Remove the payments on this bill before voiding it");
            }
            journal.reverse(orgId, entityId, bill.journalEntryId(), null, "Void bill");
            db.sql("update ar_ap.bill set status = 'void' where id = ?").param(billId).update();
            return loadBill(entityId, billId);
        });
    }

    // ---------------- payments ----------------

    public PayableModels.BillPayment payBills(UUID orgId, UUID entityId, UUID vendorId, LocalDate paidDate,
                                              UUID paymentAccountId, String method, String reference,
                                              List<NewApplication> applications) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        if (applications.isEmpty()) {
            throw new IllegalArgumentException("A payment must be applied to at least one bill");
        }
        return orgScope.call(orgId, () -> {
            findVendor(entityId, vendorId);
            Account paymentAccount = accounts.get(orgId, entityId, paymentAccountId);
            boolean usable = (paymentAccount.type() == AccountType.asset || paymentAccount.type() == AccountType.liability)
                    && !paymentAccount.isHeader() && !paymentAccount.isArchived();
            if (!usable) {
                throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                        "Pay from an active bank (asset) or credit-card (liability) account");
            }
            Account payable = payableAccount(orgId, entityId);

            Money total = Money.zero(entity.baseCurrency());
            for (NewApplication application : applications) {
                if (!application.amount().isPositive()) {
                    throw new IllegalArgumentException("Applied amounts must be positive");
                }
                PayableModels.Bill bill = lockBill(entityId, application.billId());
                if (!bill.vendorId().equals(vendorId)) {
                    throw new BusinessRuleException("WRONG_VENDOR", "That bill belongs to a different vendor");
                }
                if (bill.status().equals("draft") || bill.status().equals("void")) {
                    throw new BusinessRuleException("BILL_NOT_OPEN", "Bill is " + bill.status());
                }
                if (application.amount().compareTo(bill.balanceDue()) > 0) {
                    throw new BusinessRuleException("OVERPAYMENT",
                            "That bill has only " + bill.balanceDue().toDecimalString() + " outstanding");
                }
                total = total.add(application.amount());
            }

            UUID paymentId = Ids.newId();
            JournalEntry entry = journal.postFromSource(orgId, entityId, paidDate, "Vendor payment", List.of(
                    new JournalService.NewLine(payable.id(), total, null),
                    new JournalService.NewLine(paymentAccountId, total.negate(), null)), "bill_payment", paymentId);
            db.sql("""
                    insert into ar_ap.bill_payment (id, org_id, entity_id, vendor_id, paid_date, amount_minor, currency,
                                                    payment_account_id, method, reference, journal_entry_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(paymentId, orgId, entityId, vendorId, paidDate, total.minorUnits(), total.currency(),
                            paymentAccountId, method, reference, entry.id())
                    .update();
            for (NewApplication application : applications) {
                db.sql("""
                        insert into ar_ap.bill_payment_application (id, org_id, bill_payment_id, bill_id, amount_minor)
                        values (?, ?, ?, ?, ?)""")
                        .params(Ids.newId(), orgId, paymentId, application.billId(), application.amount().minorUnits())
                        .update();
                refreshBillStatus(entityId, application.billId());
            }
            return loadPayment(entityId, paymentId);
        });
    }

    // ---------------- reports ----------------

    public BillingModels.AgingReport aging(UUID orgId, UUID entityId, LocalDate asOf) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        return orgScope.call(orgId, () -> {
            List<Map<String, Object>> rows = db.sql("""
                    select b.id, b.due_date, b.total_minor, v.id as vendor_id, v.name as vendor_name,
                           coalesce((select sum(pa.amount_minor) from ar_ap.bill_payment_application pa
                                     join ar_ap.bill_payment p on p.id = pa.bill_payment_id
                                     where pa.bill_id = b.id and p.paid_date <= :asOf), 0) as paid_minor
                    from ar_ap.bill b join ar_ap.vendor v on v.id = b.vendor_id
                    where b.entity_id = :entity and b.status not in ('draft', 'void') and b.bill_date <= :asOf
                    order by v.name, b.due_date""")
                    .param("entity", entityId).param("asOf", asOf).query().listOfRows();

            Map<UUID, long[]> byVendor = new LinkedHashMap<>();
            Map<UUID, String> names = new LinkedHashMap<>();
            long[] totals = new long[5];
            for (Map<String, Object> row : rows) {
                long open = ((Number) row.get("total_minor")).longValue() - ((Number) row.get("paid_minor")).longValue();
                if (open <= 0) {
                    continue;
                }
                UUID vendorId = (UUID) row.get("vendor_id");
                names.put(vendorId, (String) row.get("vendor_name"));
                long[] buckets = byVendor.computeIfAbsent(vendorId, k -> new long[5]);
                LocalDate due = date(row.get("due_date"));
                long daysLate = java.time.temporal.ChronoUnit.DAYS.between(due, asOf);
                int bucket = daysLate <= 0 ? 0 : daysLate <= 30 ? 1 : daysLate <= 60 ? 2 : daysLate <= 90 ? 3 : 4;
                buckets[bucket] += open;
                totals[bucket] += open;
            }
            List<BillingModels.AgingBucket> vendors = byVendor.entrySet().stream()
                    .map(e -> bucket(e.getKey(), names.get(e.getKey()), e.getValue(), ccy)).toList();
            return new BillingModels.AgingReport(asOf, ccy, vendors, bucket(null, "All vendors", totals, ccy));
        });
    }

    public PayableModels.Form1099Report form1099Candidates(UUID orgId, UUID entityId, int taxYear) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Optional<Money> threshold = thresholds.forYear(taxYear, ccy);
        LocalDate from = LocalDate.of(taxYear, 1, 1);
        LocalDate to = LocalDate.of(taxYear, 12, 31);

        return orgScope.call(orgId, () -> {
            List<Map<String, Object>> rows = db.sql("""
                    select v.id as vendor_id, v.name, v.tax_id_last4, v.tax_classification,
                           coalesce(sum(p.amount_minor), 0) as paid_minor
                    from ar_ap.vendor v
                    left join ar_ap.bill_payment p on p.vendor_id = v.id and p.paid_date between :from and :to
                    where v.entity_id = :entity and v.is_1099_vendor
                    group by v.id, v.name, v.tax_id_last4, v.tax_classification
                    order by coalesce(sum(p.amount_minor), 0) desc, v.name""")
                    .param("entity", entityId).param("from", from).param("to", to).query().listOfRows();

            List<PayableModels.Form1099Candidate> candidates = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Money paid = Money.ofMinor(((Number) row.get("paid_minor")).longValue(), ccy);
                boolean meets = threshold.map(t -> paid.compareTo(t) >= 0).orElse(false);
                List<String> missing = new ArrayList<>();
                if (meets) {
                    if (row.get("tax_id_last4") == null) {
                        missing.add("Taxpayer ID (collect a W-9)");
                    }
                    if (row.get("tax_classification") == null) {
                        missing.add("Tax classification (collect a W-9)");
                    }
                }
                candidates.add(new PayableModels.Form1099Candidate((UUID) row.get("vendor_id"), (String) row.get("name"),
                        paid, meets, List.copyOf(missing)));
            }
            String note = threshold.isPresent()
                    ? "Amounts are payments made during " + taxYear + " (cash basis)."
                    : "No published 1099-NEC threshold for " + taxYear + " is on file, so Solid will not judge which "
                            + "vendors need a form. Add the amount to tax-rules/form-1099-nec-thresholds.json once the IRS publishes it.";
            return new PayableModels.Form1099Report(taxYear, threshold.isPresent(), threshold.orElse(null),
                    thresholds.source(), note, List.copyOf(candidates));
        });
    }

    // ---------------- internals ----------------

    private static BillingModels.AgingBucket bucket(UUID id, String name, long[] values, String ccy) {
        long total = values[0] + values[1] + values[2] + values[3] + values[4];
        return new BillingModels.AgingBucket(id, name, Money.ofMinor(values[0], ccy), Money.ofMinor(values[1], ccy),
                Money.ofMinor(values[2], ccy), Money.ofMinor(values[3], ccy), Money.ofMinor(values[4], ccy),
                Money.ofMinor(total, ccy));
    }

    private void replaceLines(UUID orgId, UUID entityId, String currency, UUID billId, List<NewBillLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A bill needs at least one line");
        }
        Money total = Money.zero(currency);
        int lineNo = 1;
        for (NewBillLine line : lines) {
            if (!line.amount().isPositive()) {
                throw new IllegalArgumentException("Bill line amounts must be positive");
            }
            if (!line.amount().currency().equals(currency)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Bill lines must be in " + currency);
            }
            Account account = accounts.get(orgId, entityId, line.expenseAccountId());
            boolean usable = (account.type() == AccountType.expense || account.type() == AccountType.asset)
                    && !account.isHeader() && !account.isArchived();
            if (!usable) {
                throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                        "Bill lines must use an active, non-header expense or asset account");
            }
            total = total.add(line.amount());
            db.sql("""
                    insert into ar_ap.bill_line (id, org_id, bill_id, line_no, description, amount_minor, expense_account_id)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), orgId, billId, lineNo++, line.description().trim(), line.amount().minorUnits(),
                            line.expenseAccountId())
                    .update();
        }
        db.sql("update ar_ap.bill set total_minor = ? where id = ?").params(total.minorUnits(), billId).update();
    }

    private Account payableAccount(UUID orgId, UUID entityId) {
        return accounts.list(orgId, entityId).stream()
                .filter(a -> a.type() == AccountType.liability && "ap".equals(a.subtype()) && !a.isArchived() && !a.isHeader())
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("NO_PAYABLE_ACCOUNT",
                        "Add a liability account with subtype 'ap' (Accounts Payable) first"));
    }

    private static LocalDate dueDate(LocalDate billDate, String terms) {
        return switch (terms) {
            case "due_on_receipt" -> billDate;
            case "net_15" -> billDate.plusDays(15);
            case "net_30" -> billDate.plusDays(30);
            case "net_60" -> billDate.plusDays(60);
            default -> throw new IllegalArgumentException("Unknown terms: " + terms);
        };
    }

    private void refreshBillStatus(UUID entityId, UUID billId) {
        PayableModels.Bill bill = loadBill(entityId, billId);
        String status = bill.balanceDue().isZero() ? "paid" : bill.amountPaid().isPositive() ? "partially_paid" : "open";
        db.sql("update ar_ap.bill set status = ? where id = ?").params(status, billId).update();
    }

    private PayableModels.Vendor findVendor(UUID entityId, UUID vendorId) {
        return db.sql(VENDOR_SELECT + " where entity_id = ? and id = ?").params(entityId, vendorId)
                .query(PayableModels.Vendor.class).optional()
                .orElseThrow(() -> new NotFoundException("Vendor " + vendorId + " not found"));
    }

    private PayableModels.Bill lockBill(UUID entityId, UUID billId) {
        db.sql("select id from ar_ap.bill where entity_id = ? and id = ? for update").params(entityId, billId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Bill " + billId + " not found"));
        return loadBill(entityId, billId);
    }

    private PayableModels.Bill loadBill(UUID entityId, UUID billId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, vendor_id, vendor_reference, bill_date, due_date, terms, memo, total_minor,
                       currency, status, journal_entry_id,
                       coalesce((select sum(amount_minor) from ar_ap.bill_payment_application where bill_id = ar_ap.bill.id), 0) as paid_minor
                from ar_ap.bill where entity_id = ? and id = ?""")
                .params(entityId, billId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Bill " + billId + " not found"));
        String currency = ((String) row.get("currency")).trim();
        Money total = Money.ofMinor(((Number) row.get("total_minor")).longValue(), currency);
        Money paid = Money.ofMinor(((Number) row.get("paid_minor")).longValue(), currency);
        List<PayableModels.BillLine> lines = db.sql("""
                select id, line_no, description, amount_minor, expense_account_id from ar_ap.bill_line
                where bill_id = ? order by line_no""")
                .param(billId)
                .query((rs, n) -> new PayableModels.BillLine(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                        rs.getString("description"), Money.ofMinor(rs.getLong("amount_minor"), currency),
                        rs.getObject("expense_account_id", UUID.class)))
                .list();
        return new PayableModels.Bill((UUID) row.get("id"), (UUID) row.get("entity_id"), (UUID) row.get("vendor_id"),
                (String) row.get("vendor_reference"), date(row.get("bill_date")), date(row.get("due_date")),
                (String) row.get("terms"), (String) row.get("memo"), total, paid, total.subtract(paid),
                (String) row.get("status"), (UUID) row.get("journal_entry_id"), lines);
    }

    private PayableModels.BillPayment loadPayment(UUID entityId, UUID paymentId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, vendor_id, paid_date, amount_minor, currency, payment_account_id, method,
                       reference, journal_entry_id
                from ar_ap.bill_payment where entity_id = ? and id = ?""")
                .params(entityId, paymentId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found"));
        String currency = ((String) row.get("currency")).trim();
        List<PayableModels.BillPaymentApplication> applications = db.sql("""
                select bill_id, amount_minor from ar_ap.bill_payment_application where bill_payment_id = ? order by bill_id""")
                .param(paymentId)
                .query((rs, n) -> new PayableModels.BillPaymentApplication(rs.getObject("bill_id", UUID.class),
                        Money.ofMinor(rs.getLong("amount_minor"), currency)))
                .list();
        return new PayableModels.BillPayment((UUID) row.get("id"), (UUID) row.get("entity_id"), (UUID) row.get("vendor_id"),
                date(row.get("paid_date")), Money.ofMinor(((Number) row.get("amount_minor")).longValue(), currency),
                (UUID) row.get("payment_account_id"), (String) row.get("method"), (String) row.get("reference"),
                (UUID) row.get("journal_entry_id"), applications);
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value;
    }

    private static final String VENDOR_SELECT = """
            select id, entity_id, name, email, phone, address, tax_id_last4, tax_classification,
                   is_1099_vendor as is1099Vendor, default_expense_account_id, is_archived, created_at
            from ar_ap.vendor""";
}
