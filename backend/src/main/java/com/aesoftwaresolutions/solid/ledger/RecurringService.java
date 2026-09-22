package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Entries that repeat on a schedule. Nothing posts on a timer: a person (or a cron job calling the API) runs a
 * template through a date, and every occurrence due in that range is posted exactly once.
 */
@Service
public class RecurringService {

    /** How many occurrences one run may post, so a template starting in 1990 cannot flood the ledger. */
    private static final int MAX_OCCURRENCES_PER_RUN = 120;

    public record NewLine(UUID accountId, Money amount, String memo) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;

    RecurringService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts,
                     JournalService journal) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
    }

    public RecurringModels.Recurring create(UUID orgId, UUID entityId, String name, String memo, String frequency,
                                            LocalDate startDate, LocalDate endDate, Integer dayOfMonth,
                                            List<NewLine> lines) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        if (!List.of("monthly", "quarterly", "annual").contains(frequency)) {
            throw new IllegalArgumentException("frequency must be monthly, quarterly or annual");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A recurring entry needs at least two lines");
        }
        long total = 0;
        for (NewLine line : lines) {
            if (!line.amount().currency().equals(ccy)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Amounts must be in " + ccy);
            }
            if (line.amount().minorUnits() == 0) {
                throw new IllegalArgumentException("A line cannot be zero");
            }
            Account account = accounts.get(orgId, entityId, line.accountId());
            if (account.isHeader() || account.isArchived()) {
                throw new IllegalArgumentException("Account " + account.code() + " cannot be posted to");
            }
            total += line.amount().minorUnits();
        }
        if (total != 0) {
            throw new BusinessRuleException("ENTRY_NOT_BALANCED",
                    "Debits and credits must balance; they differ by " + Money.ofMinor(total, ccy).toDecimalString());
        }
        int day = dayOfMonth == null ? startDate.getDayOfMonth() : dayOfMonth;
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("dayOfMonth must be between 1 and 31");
        }

        UUID id = Ids.newId();
        orgScope.run(orgId, () -> {
            db.sql("""
                    insert into gl.recurring_entry (id, org_id, entity_id, name, memo, frequency, start_date, end_date,
                                                    day_of_month)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, name.trim(), memo, frequency, startDate, endDate, day).update();
            int lineNo = 1;
            for (NewLine line : lines) {
                db.sql("""
                        insert into gl.recurring_line (id, org_id, recurring_id, line_no, account_id, amount_minor,
                                                       currency, memo)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(Ids.newId(), orgId, id, lineNo++, line.accountId(), line.amount().minorUnits(),
                                line.amount().currency(), line.memo())
                        .update();
            }
        });
        return get(orgId, entityId, id);
    }

    public List<RecurringModels.Recurring> list(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        List<UUID> ids = orgScope.call(orgId, () ->
                db.sql("select id from gl.recurring_entry where entity_id = ? order by name")
                        .param(entityId).query(UUID.class).list());
        List<RecurringModels.Recurring> result = new ArrayList<>(ids.size());
        ids.forEach(id -> result.add(get(orgId, entityId, id)));
        return result;
    }

    public RecurringModels.Recurring get(UUID orgId, UUID entityId, UUID recurringId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, recurringId));
    }

    public RecurringModels.Recurring deactivate(UUID orgId, UUID entityId, UUID recurringId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            int updated = db.sql("update gl.recurring_entry set is_active = false where entity_id = ? and id = ?")
                    .params(entityId, recurringId).update();
            if (updated == 0) {
                throw new NotFoundException("Recurring entry " + recurringId + " not found");
            }
            return load(entityId, recurringId);
        });
    }

    /** One occurrence a template will post if it is run, with the lines it would post. */
    public record Upcoming(UUID templateId, String name, LocalDate date, List<RecurringModels.Line> lines) {
    }

    /**
     * What the active templates would post between now and {@code through}, without posting anything. Used by
     * the "what is coming" page (spec 053), so that page cannot disagree with what a run would do.
     */
    public List<Upcoming> upcoming(UUID orgId, UUID entityId, LocalDate through) {
        List<Upcoming> result = new ArrayList<>();
        for (RecurringModels.Recurring template : list(orgId, entityId)) {
            if (!template.active()) {
                continue;
            }
            LocalDate lastPosted = orgScope.call(orgId, () -> db.sql(
                    "select max(occurrence_date) from gl.recurring_occurrence where recurring_id = ?")
                    .param(template.id()).query(LocalDate.class).optional().orElse(null));
            for (LocalDate date : dueDates(template, through, lastPosted)) {
                result.add(new Upcoming(template.id(), template.name(), date, template.lines()));
            }
        }
        return result;
    }

    /** Posts every occurrence due on or before {@code through} that has not been posted yet. */
    public RecurringModels.RunResult run(UUID orgId, UUID entityId, LocalDate through) {
        orgs.getEntity(orgId, entityId);
        List<RecurringModels.Posted> posted = new ArrayList<>();
        List<RecurringModels.Skipped> skipped = new ArrayList<>();

        for (RecurringModels.Recurring template : list(orgId, entityId)) {
            if (!template.active()) {
                continue;
            }
            LocalDate lastPosted = orgScope.call(orgId, () -> db.sql(
                    "select max(occurrence_date) from gl.recurring_occurrence where recurring_id = ?")
                    .param(template.id()).query(LocalDate.class).optional().orElse(null));
            for (LocalDate date : dueDates(template, through, lastPosted)) {
                List<JournalService.NewLine> lines = template.lines().stream()
                        .map(line -> new JournalService.NewLine(line.accountId(), line.amount(), line.memo()))
                        .toList();
                try {
                    // The check, the posting and the occurrence row are one transaction, and the journal's own
                    // idempotency key makes a second attempt return the first entry instead of posting again.
                    // Two runs at the same moment therefore cannot both book the rent.
                    Optional<JournalEntry> entry = orgScope.call(orgId, () -> {
                        boolean alreadyPosted = db.sql("""
                                select count(*) from gl.recurring_occurrence
                                where recurring_id = ? and occurrence_date = ?""")
                                .params(template.id(), date).query(Long.class).single() > 0;
                        if (alreadyPosted) {
                            return Optional.empty();
                        }
                        JournalEntry booked = journal.create(orgId, entityId, date,
                                template.memo() == null ? template.name() : template.memo(), true, lines,
                                "recurring:" + template.id() + ":" + date, "recurring", template.id(), null).entry();
                        db.sql("""
                                insert into gl.recurring_occurrence (recurring_id, occurrence_date, journal_entry_id,
                                                                    org_id)
                                values (?, ?, ?, ?)
                                on conflict (recurring_id, occurrence_date) do nothing""")
                                .params(template.id(), date, booked.id(), orgId).update();
                        return Optional.of(booked);
                    });
                    entry.ifPresent(booked -> posted.add(new RecurringModels.Posted(date, booked.id())));
                } catch (BusinessRuleException e) {
                    // One refused month (a locked period, say) must not stop the others.
                    skipped.add(new RecurringModels.Skipped(date, e.getMessage()));
                } catch (IllegalArgumentException e) {
                    skipped.add(new RecurringModels.Skipped(date, e.getMessage()));
                }
            }
        }
        return new RecurringModels.RunResult(through, List.copyOf(posted), List.copyOf(skipped));
    }

    /** The occurrence dates from the start date through {@code through}, clamped to each month's length. */
    static List<LocalDate> dueDates(RecurringModels.Recurring template, LocalDate through) {
        return dueDates(template, through, null);
    }

    /**
     * Occurrence dates that are still due: those after {@code after} (the last one posted) and on or before
     * {@code through}. At most {@link #MAX_OCCURRENCES_PER_RUN} are returned, and because the walk starts from
     * what is still outstanding rather than from the template's first month, that cap stays a per-run limit
     * however old the template gets.
     */
    static List<LocalDate> dueDates(RecurringModels.Recurring template, LocalDate through, LocalDate after) {
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
            // Jump straight to the first period after what is already posted, in whole steps from the start.
            long elapsed = java.time.temporal.ChronoUnit.MONTHS.between(month, YearMonth.from(after));
            long skip = Math.max(0, (elapsed / step) * step);
            month = month.plusMonths(skip);
        }
        for (int i = 0; i < MAX_OCCURRENCES_PER_RUN * 2 && dates.size() < MAX_OCCURRENCES_PER_RUN; i++) {
            LocalDate date = onDay(month, template.dayOfMonth());
            month = month.plusMonths(step);
            if (date.isAfter(last)) {
                break;
            }
            if (date.isBefore(template.startDate()) || (after != null && !date.isAfter(after))) {
                continue;
            }
            dates.add(date);
        }
        return dates;
    }

    /** The 31st in a 30-day month is the 30th — a recurring entry never slides into the next month. */
    private static LocalDate onDay(YearMonth month, int dayOfMonth) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    /** Must run inside {@link OrgScope}. */
    private RecurringModels.Recurring load(UUID entityId, UUID recurringId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, name, memo, frequency, start_date, end_date, day_of_month, is_active
                from gl.recurring_entry where entity_id = ? and id = ?""")
                .params(entityId, recurringId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Recurring entry " + recurringId + " not found"));

        List<RecurringModels.Line> lines = db.sql("""
                select line_no, account_id, amount_minor, currency, memo from gl.recurring_line
                where recurring_id = ? order by line_no""")
                .param(recurringId)
                .query((rs, n) -> new RecurringModels.Line(rs.getInt("line_no"), rs.getObject("account_id", UUID.class),
                        Money.ofMinor(rs.getLong("amount_minor"), rs.getString("currency").trim()),
                        rs.getString("memo")))
                .list();

        LocalDate lastPosted = db.sql("""
                select max(occurrence_date) from gl.recurring_occurrence where recurring_id = ?""")
                .param(recurringId).query(LocalDate.class).optional().orElse(null);

        RecurringModels.Recurring template = new RecurringModels.Recurring((UUID) row.get("id"),
                (UUID) row.get("entity_id"), (String) row.get("name"), (String) row.get("memo"),
                (String) row.get("frequency"), date(row.get("start_date")), date(row.get("end_date")),
                ((Number) row.get("day_of_month")).intValue(), (Boolean) row.get("is_active"), null, lines);

        return new RecurringModels.Recurring(template.id(), template.entityId(), template.name(), template.memo(),
                template.frequency(), template.startDate(), template.endDate(), template.dayOfMonth(),
                template.active(), nextDate(template, lastPosted), lines);
    }

    /** The first scheduled date after whatever has already been posted, or null when the template is finished. */
    private static LocalDate nextDate(RecurringModels.Recurring template, LocalDate lastPosted) {
        if (!template.active()) {
            return null;
        }
        LocalDate horizon = template.endDate() != null ? template.endDate() : LocalDate.of(2100, 1, 1);
        return dueDates(template, horizon, lastPosted).stream().findFirst().orElse(null);
    }

    private static LocalDate date(Object value) {
        return Optional.ofNullable(value)
                .map(v -> v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v)
                .orElse(null);
    }
}
