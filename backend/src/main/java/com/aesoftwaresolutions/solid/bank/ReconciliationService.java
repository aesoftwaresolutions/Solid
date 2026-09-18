package com.aesoftwaresolutions.solid.bank;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.platform.RequestContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Bank reconciliation: tick off the ledger lines that appear on a bank statement until the cleared balance
 * matches the statement's ending balance, then freeze that period as reconciled.
 */
@Service
public class ReconciliationService {

    public record Status(UUID id, UUID bankAccountId, LocalDate statementDate, Money statementEndingBalance,
                         Money beginningBalance, Money clearedBalance, Money difference, int clearedCount,
                         String status, OffsetDateTime completedAt) {
    }

    public record Candidate(UUID lineId, UUID entryId, LocalDate entryDate, String memo, Money amount, boolean cleared) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final BankService bank;
    private final JournalService journal;
    private final AuditLog audit;

    ReconciliationService(JdbcClient db, OrgScope orgScope, OrgService orgs, BankService bank, JournalService journal,
                          AuditLog audit) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.bank = bank;
        this.journal = journal;
        this.audit = audit;
    }

    public Status start(UUID orgId, UUID entityId, UUID bankAccountId, LocalDate statementDate, Money endingBalance) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        if (!endingBalance.currency().equals(entity.baseCurrency())) {
            throw new BusinessRuleException("CURRENCY_MISMATCH",
                    "Statement balance must be in " + entity.baseCurrency());
        }
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        return orgScope.call(orgId, () -> {
            boolean open = db.sql("select exists (select 1 from bank.reconciliation where bank_account_id = ? and status = 'in_progress')")
                    .param(bankAccountId).query(Boolean.class).single();
            if (open) {
                throw new BusinessRuleException("RECONCILIATION_IN_PROGRESS",
                        "Finish or undo the reconciliation already in progress");
            }
            Map<String, Object> previous = latestCompleted(bankAccountId);
            if (previous != null) {
                LocalDate previousDate = toLocalDate(previous.get("statement_date"));
                if (!statementDate.isAfter(previousDate)) {
                    throw new BusinessRuleException("STATEMENT_DATE_TOO_EARLY",
                            "Statement date must be after the last reconciled statement (" + previousDate + ")");
                }
            }
            long beginning = previous == null ? 0L : ((Number) previous.get("statement_ending_minor")).longValue();
            UUID id = Ids.newId();
            db.sql("""
                    insert into bank.reconciliation (id, org_id, bank_account_id, statement_date, statement_ending_minor,
                                                     beginning_minor, currency, status)
                    values (?, ?, ?, ?, ?, ?, ?, 'in_progress')""")
                    .params(id, orgId, bankAccountId, statementDate, endingBalance.minorUnits(), beginning,
                            entity.baseCurrency())
                    .update();
            return status(entityId, account, id);
        });
    }

    public List<Status> history(UUID orgId, UUID entityId, UUID bankAccountId) {
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        return orgScope.call(orgId, () -> db.sql("select id from bank.reconciliation where bank_account_id = ? order by statement_date desc, created_at desc")
                .param(bankAccountId).query(UUID.class).list().stream()
                .map(id -> status(entityId, account, id)).toList());
    }

    public Status get(UUID orgId, UUID entityId, UUID bankAccountId, UUID reconciliationId) {
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        return orgScope.call(orgId, () -> status(entityId, account, reconciliationId));
    }

    public List<Candidate> candidates(UUID orgId, UUID entityId, UUID bankAccountId, UUID reconciliationId) {
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        return orgScope.call(orgId, () -> {
            Map<String, Object> rec = load(reconciliationId);
            LocalDate statementDate = toLocalDate(rec.get("statement_date"));
            Set<UUID> clearedHere = clearedLineIds(reconciliationId);
            Set<UUID> clearedElsewhere = new HashSet<>(db.sql("""
                    select l.journal_line_id from bank.reconciliation_line l
                    join bank.reconciliation r on r.id = l.reconciliation_id
                    where r.bank_account_id = ? and l.reconciliation_id <> ?""")
                    .params(bankAccountId, reconciliationId).query(UUID.class).list());

            List<Candidate> result = new ArrayList<>();
            for (JournalService.PostedLine line : journal.postedLinesForAccount(orgId, entityId, account.glAccountId(),
                    statementDate)) {
                if (clearedElsewhere.contains(line.lineId())) {
                    continue;
                }
                result.add(new Candidate(line.lineId(), line.entryId(), line.entryDate(), line.memo(), line.amount(),
                        clearedHere.contains(line.lineId())));
            }
            return result;
        });
    }

    public Status setCleared(UUID orgId, UUID entityId, UUID bankAccountId, UUID reconciliationId, List<UUID> lineIds,
                            boolean cleared) {
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        if (lineIds.isEmpty() || lineIds.size() > 1000) {
            throw new IllegalArgumentException("Send between 1 and 1000 line ids");
        }
        return orgScope.call(orgId, () -> {
            Map<String, Object> rec = requireOpen(reconciliationId);
            LocalDate statementDate = toLocalDate(rec.get("statement_date"));
            Set<UUID> allowed = journal.postedLinesForAccount(orgId, entityId, account.glAccountId(), statementDate).stream()
                    .map(JournalService.PostedLine::lineId).collect(java.util.stream.Collectors.toSet());
            for (UUID lineId : lineIds) {
                if (!allowed.contains(lineId)) {
                    throw new BusinessRuleException("INVALID_LINE",
                            "Line " + lineId + " is not a posted line of this bank account on or before " + statementDate);
                }
                if (cleared) {
                    int inserted = db.sql("""
                            insert into bank.reconciliation_line (reconciliation_id, journal_line_id, org_id)
                            values (?, ?, ?) on conflict do nothing""")
                            .params(reconciliationId, lineId, orgId).update();
                    if (inserted == 0 && !clearedLineIds(reconciliationId).contains(lineId)) {
                        throw new BusinessRuleException("LINE_ALREADY_RECONCILED",
                                "Line " + lineId + " was cleared by another reconciliation");
                    }
                } else {
                    db.sql("delete from bank.reconciliation_line where reconciliation_id = ? and journal_line_id = ?")
                            .params(reconciliationId, lineId).update();
                }
            }
            return status(entityId, account, reconciliationId);
        });
    }

    public Status complete(UUID orgId, UUID entityId, UUID bankAccountId, UUID reconciliationId) {
        BankModels.BankAccount account = bankAccount(orgId, entityId, bankAccountId);
        Status status = orgScope.call(orgId, () -> {
            requireOpen(reconciliationId);
            Status current = status(entityId, account, reconciliationId);
            if (!current.difference().isZero()) {
                throw new BusinessRuleException("NOT_BALANCED",
                        "Difference is " + current.difference().toDecimalString() + " " + current.difference().currency()
                                + "; clear or unclear lines until it is 0.00");
            }
            db.sql("update bank.reconciliation set status = 'completed', completed_at = now(), completed_by = ? where id = ?")
                    .params(RequestContext.current().map(RequestContext.Caller::userId).orElse(null), reconciliationId)
                    .update();
            return status(entityId, account, reconciliationId);
        });
        audit.record(AuditLog.Actor.current(), orgId, "reconciliation_completed", "reconciliation", reconciliationId,
                Map.of("bankAccountId", bankAccountId.toString(), "statementDate", status.statementDate().toString(),
                        "endingBalance", status.statementEndingBalance().toDecimalString()));
        return status;
    }

    public void undo(UUID orgId, UUID entityId, UUID bankAccountId, UUID reconciliationId) {
        bankAccount(orgId, entityId, bankAccountId);
        orgScope.run(orgId, () -> {
            Map<String, Object> rec = load(reconciliationId);
            UUID latest = db.sql("select id from bank.reconciliation where bank_account_id = ? order by statement_date desc, created_at desc limit 1")
                    .param(bankAccountId).query(UUID.class).single();
            if (!latest.equals(reconciliationId)) {
                throw new BusinessRuleException("NOT_LATEST_RECONCILIATION",
                        "Only the most recent reconciliation can be undone");
            }
            db.sql("delete from bank.reconciliation where id = ?").param(reconciliationId).update();
            audit.record(AuditLog.Actor.current(), orgId, "reconciliation_undone", "reconciliation", reconciliationId,
                    Map.of("bankAccountId", bankAccountId.toString(), "statementDate", String.valueOf(rec.get("statement_date"))));
        });
    }

    // ---------------- internals (inside org scope) ----------------

    private BankModels.BankAccount bankAccount(UUID orgId, UUID entityId, UUID bankAccountId) {
        return bank.listBankAccounts(orgId, entityId).stream()
                .filter(a -> a.id().equals(bankAccountId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Bank account " + bankAccountId + " not found"));
    }

    /** JDBC returns java.sql.Date/Timestamp for date columns in generic row maps. */
    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime t -> t;
            case java.sql.Timestamp t -> t.toInstant().atOffset(java.time.ZoneOffset.UTC);
            default -> OffsetDateTime.parse(value.toString());
        };
    }

    private Map<String, Object> load(UUID reconciliationId) {
        return db.sql("""
                select id, bank_account_id, statement_date, statement_ending_minor, beginning_minor, currency, status,
                       completed_at
                from bank.reconciliation where id = ?""")
                .param(reconciliationId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Reconciliation " + reconciliationId + " not found"));
    }

    private Map<String, Object> requireOpen(UUID reconciliationId) {
        Map<String, Object> rec = load(reconciliationId);
        if (!"in_progress".equals(rec.get("status"))) {
            throw new BusinessRuleException("RECONCILIATION_COMPLETED",
                    "This reconciliation is completed; undo it first to make changes");
        }
        return rec;
    }

    private Map<String, Object> latestCompleted(UUID bankAccountId) {
        return db.sql("""
                select statement_date, statement_ending_minor from bank.reconciliation
                where bank_account_id = ? and status = 'completed' order by statement_date desc limit 1""")
                .param(bankAccountId).query().listOfRows().stream().findFirst().orElse(null);
    }

    private Set<UUID> clearedLineIds(UUID reconciliationId) {
        return new HashSet<>(db.sql("select journal_line_id from bank.reconciliation_line where reconciliation_id = ?")
                .param(reconciliationId).query(UUID.class).list());
    }

    private Status status(UUID entityId, BankModels.BankAccount account, UUID reconciliationId) {
        Map<String, Object> rec = load(reconciliationId);
        if (!account.id().equals(rec.get("bank_account_id"))) {
            throw new NotFoundException("Reconciliation " + reconciliationId + " not found");
        }
        String currency = ((String) rec.get("currency")).trim();
        Money beginning = Money.ofMinor(((Number) rec.get("beginning_minor")).longValue(), currency);
        Money ending = Money.ofMinor(((Number) rec.get("statement_ending_minor")).longValue(), currency);
        Map<String, Object> cleared = db.sql("""
                select count(*) as line_count, coalesce(sum(l.amount_minor), 0) as total
                from bank.reconciliation_line rl join gl.journal_line l on l.id = rl.journal_line_id
                where rl.reconciliation_id = ?""").param(reconciliationId).query().singleRow();
        Money clearedBalance = beginning.add(Money.ofMinor(((Number) cleared.get("total")).longValue(), currency));
        return new Status(reconciliationId, account.id(), toLocalDate(rec.get("statement_date")), ending, beginning,
                clearedBalance, ending.subtract(clearedBalance), ((Number) cleared.get("line_count")).intValue(),
                (String) rec.get("status"), toOffsetDateTime(rec.get("completed_at")));
    }
}
