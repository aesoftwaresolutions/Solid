package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Invoices that repeat: a retainer, a monthly service, a quarterly licence.
 *
 * <p>Nothing happens on a timer. A person — or a cron job calling the API — runs the templates through a
 * date, and every occurrence due on or before it is created exactly once. Each occurrence makes a
 * <em>draft</em> invoice: an invoice is a statement to a customer, and somebody should look at it before it
 * goes.
 */
@Service
public class RecurringInvoiceService {

    /** How many occurrences one run may create, so a template that starts in 2019 cannot flood the books. */
    private static final int MAX_OCCURRENCES_PER_RUN = 120;

    public record NewLine(String description, BigDecimal quantity, Money unitPrice, UUID incomeAccountId,
                          UUID taxRateId) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final BillingService billing;

    RecurringInvoiceService(JdbcClient db, OrgScope orgScope, OrgService orgs, BillingService billing) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.billing = billing;
    }

    public RecurringInvoiceModels.RecurringInvoice create(UUID orgId, UUID entityId, UUID customerId, String name,
                                                          String memo, String terms, String frequency,
                                                          LocalDate startDate, LocalDate endDate,
                                                          Integer dayOfMonth, List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        if (!List.of("monthly", "quarterly", "annual").contains(frequency)) {
            throw new IllegalArgumentException("frequency must be monthly, quarterly or annual");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A recurring invoice needs at least one line");
        }
        for (NewLine line : lines) {
            if (!line.unitPrice().currency().equals(ccy)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Prices must be in " + ccy);
            }
            if (line.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Quantity must be more than zero");
            }
        }
        int day = dayOfMonth == null ? startDate.getDayOfMonth() : dayOfMonth;
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("dayOfMonth must be between 1 and 31");
        }
        // The customer has to exist in this entity; billing's own lookup is the one that decides that.
        billing.getCustomer(orgId, entityId, customerId);

        UUID id = Ids.newId();
        orgScope.run(orgId, () -> {
            db.sql("""
                    insert into ar_ap.recurring_invoice (id, org_id, entity_id, customer_id, name, memo, terms,
                                                         frequency, start_date, end_date, day_of_month)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, customerId, name.trim(), memo, terms, frequency, startDate,
                            endDate, day)
                    .update();
            int lineNo = 1;
            for (NewLine line : lines) {
                db.sql("""
                        insert into ar_ap.recurring_invoice_line (id, org_id, recurring_invoice_id, line_no,
                                                                  description, quantity, unit_price_minor,
                                                                  income_account_id, tax_rate_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(Ids.newId(), orgId, id, lineNo++, line.description().trim(), line.quantity(),
                                line.unitPrice().minorUnits(), line.incomeAccountId(), line.taxRateId())
                        .update();
            }
        });
        return get(orgId, entityId, id);
    }

    public List<RecurringInvoiceModels.RecurringInvoice> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        List<UUID> ids = orgScope.call(orgId, () ->
                db.sql("select id from ar_ap.recurring_invoice where entity_id = ? order by name")
                        .param(entityId).query(UUID.class).list());
        List<RecurringInvoiceModels.RecurringInvoice> result = new ArrayList<>(ids.size());
        ids.forEach(id -> result.add(get(orgId, entityId, id)));
        return result;
    }

    public RecurringInvoiceModels.RecurringInvoice get(UUID orgId, UUID entityId, UUID id) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, id, entity.baseCurrency()));
    }

    public RecurringInvoiceModels.RecurringInvoice deactivate(UUID orgId, UUID entityId, UUID id) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            int updated = db.sql("update ar_ap.recurring_invoice set is_active = false where entity_id = ? and id = ?")
                    .params(entityId, id).update();
            if (updated == 0) {
                throw new NotFoundException("Recurring invoice " + id + " not found");
            }
            return load(entityId, id, entity.baseCurrency());
        });
    }

    /** Creates every occurrence due on or before {@code through} that does not exist yet. */
    public RecurringInvoiceModels.RunResult run(UUID orgId, UUID entityId, LocalDate through) {
        orgs.getEntity(orgId, entityId);
        List<RecurringInvoiceModels.Created> created = new ArrayList<>();
        List<RecurringInvoiceModels.Skipped> skipped = new ArrayList<>();

        for (RecurringInvoiceModels.RecurringInvoice template : list(orgId, entityId)) {
            if (!template.active()) {
                continue;
            }
            // Archiving a customer means "we have stopped dealing with them", so a template pointing at one
            // must stop billing rather than quietly carry on.
            if (billing.getCustomer(orgId, entityId, template.customerId()).isArchived()) {
                for (LocalDate date : dueDates(template, through, template.lastCreated())) {
                    skipped.add(new RecurringInvoiceModels.Skipped(template.id(), date,
                            "The customer " + template.customerName() + " is archived, so nothing was billed. "
                                    + "Un-archive them, or stop this template."));
                }
                continue;
            }
            for (LocalDate date : dueDates(template, through, template.lastCreated())) {
                try {
                    // The check, the invoice and the occurrence row are one transaction, so two runs at the
                    // same moment cannot both bill the same month.
                    Optional<BillingModels.Invoice> invoice = orgScope.call(orgId, () -> {
                        boolean exists = db.sql("""
                                select count(*) from ar_ap.recurring_invoice_occurrence
                                where recurring_invoice_id = ? and occurrence_date = ?""")
                                .params(template.id(), date).query(Long.class).single() > 0;
                        if (exists) {
                            return Optional.empty();
                        }
                        List<BillingService.NewLine> lines = template.lines().stream()
                                .map(line -> new BillingService.NewLine(line.description(), line.quantity(),
                                        line.unitPrice(), line.incomeAccountId(), line.taxRateId()))
                                .toList();
                        BillingModels.Invoice draft = billing.createInvoice(orgId, entityId, template.customerId(),
                                date, template.terms(), null, template.memo(), lines);
                        db.sql("""
                                insert into ar_ap.recurring_invoice_occurrence (recurring_invoice_id,
                                        occurrence_date, invoice_id, org_id)
                                values (?, ?, ?, ?)
                                on conflict (recurring_invoice_id, occurrence_date) do nothing""")
                                .params(template.id(), date, draft.id(), orgId).update();
                        return Optional.of(draft);
                    });
                    invoice.ifPresent(draft -> created.add(new RecurringInvoiceModels.Created(template.id(), date,
                            draft.id(), draft.invoiceNumber())));
                } catch (BusinessRuleException | IllegalArgumentException | NotFoundException e) {
                    // One refused month — an archived customer, an account that can no longer be posted to —
                    // must not stop the other templates or the other months.
                    skipped.add(new RecurringInvoiceModels.Skipped(template.id(), date, e.getMessage()));
                }
            }
        }
        return new RecurringInvoiceModels.RunResult(through, List.copyOf(created), List.copyOf(skipped));
    }

    /**
     * The occurrence dates still due: after {@code after} (the last one created) and on or before
     * {@code through}, clamped to each month's length, at most {@link #MAX_OCCURRENCES_PER_RUN} of them.
     */
    static List<LocalDate> dueDates(RecurringInvoiceModels.RecurringInvoice template, LocalDate through,
                                    LocalDate after) {
        List<LocalDate> dates = new ArrayList<>();
        int step = switch (template.frequency()) {
            case "monthly" -> 1;
            case "quarterly" -> 3;
            default -> 12;
        };
        LocalDate last = template.endDate() != null && template.endDate().isBefore(through)
                ? template.endDate() : through;

        YearMonth month = YearMonth.from(template.startDate());
        if (after != null) {
            long elapsed = java.time.temporal.ChronoUnit.MONTHS.between(month, YearMonth.from(after));
            month = month.plusMonths(Math.max(0, (elapsed / step) * step));
        }
        for (int i = 0; i < MAX_OCCURRENCES_PER_RUN * 2 && dates.size() < MAX_OCCURRENCES_PER_RUN; i++) {
            LocalDate date = onDay(month, template.dayOfMonth());
            month = month.plusMonths(step);
            if (date.isBefore(template.startDate()) || (after != null && !date.isAfter(after))) {
                continue;
            }
            if (date.isAfter(last)) {
                break;
            }
            dates.add(date);
        }
        return dates;
    }

    /** The 31st of a 30-day month is the 30th: a monthly invoice should not skip April. */
    private static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    // ---------------- loading (must run inside OrgScope) ----------------

    private RecurringInvoiceModels.RecurringInvoice load(UUID entityId, UUID id, String ccy) {
        var row = db.sql("""
                select r.id, r.entity_id, r.customer_id, c.name as customer_name, r.name, r.memo, r.terms,
                       r.frequency, r.start_date, r.end_date, r.day_of_month, r.is_active,
                       (select max(occurrence_date) from ar_ap.recurring_invoice_occurrence o
                        where o.recurring_invoice_id = r.id) as last_created
                from ar_ap.recurring_invoice r join ar_ap.customer c on c.id = r.customer_id
                where r.entity_id = ? and r.id = ?""")
                .params(entityId, id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Recurring invoice " + id + " not found"));

        List<RecurringInvoiceModels.Line> lines = db.sql("""
                select id, line_no, description, quantity, unit_price_minor, income_account_id, tax_rate_id
                from ar_ap.recurring_invoice_line where recurring_invoice_id = ? order by line_no""")
                .param(id)
                .query((rs, n) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    Money unitPrice = Money.ofMinor(rs.getLong("unit_price_minor"), ccy);
                    // Quantity can carry four decimals (hours, say), so the line total is rounded once, the
                    // same way an invoice line is.
                    Money amount = unitPrice.multiply(quantity, java.math.RoundingMode.HALF_UP);
                    return new RecurringInvoiceModels.Line(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                            rs.getString("description"), quantity, unitPrice, amount,
                            rs.getObject("income_account_id", UUID.class),
                            rs.getObject("tax_rate_id", UUID.class));
                })
                .list();

        Money total = Money.zero(ccy);
        for (RecurringInvoiceModels.Line line : lines) {
            total = total.add(line.amount());
        }
        Object lastCreated = row.get("last_created");
        return new RecurringInvoiceModels.RecurringInvoice(
                (UUID) row.get("id"), (UUID) row.get("entity_id"), (UUID) row.get("customer_id"),
                (String) row.get("customer_name"), (String) row.get("name"), (String) row.get("memo"),
                (String) row.get("terms"), (String) row.get("frequency"),
                ((java.sql.Date) row.get("start_date")).toLocalDate(),
                row.get("end_date") == null ? null : ((java.sql.Date) row.get("end_date")).toLocalDate(),
                (Integer) row.get("day_of_month"), (Boolean) row.get("is_active"), lines, total,
                lastCreated == null ? null : ((java.sql.Date) lastCreated).toLocalDate());
    }
}
