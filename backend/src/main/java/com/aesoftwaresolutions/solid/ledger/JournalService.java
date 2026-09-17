package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Creates, posts and reverses journal entries. The database enforces the same rules as a safety net. */
@Service
public class JournalService {

    public record NewLine(UUID accountId, Money amount, String memo) {
    }

    public record ChainVerification(boolean valid, long postedEntries, Long firstInvalidSeq) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;

    JournalService(JdbcClient db, OrgScope orgScope, OrgService orgs) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    public record CreateResult(JournalEntry entry, boolean replayed) {
    }

    public CreateResult create(UUID orgId, UUID entityId, LocalDate entryDate, String memo, boolean post,
                               List<NewLine> lines, String idempotencyKey) {
        return create(orgId, entityId, entryDate, memo, post, lines, idempotencyKey, "manual", null, null);
    }

    CreateResult create(UUID orgId, UUID entityId, LocalDate entryDate, String memo, boolean post, List<NewLine> lines,
                        String idempotencyKey, String source, UUID sourceRef, UUID reversesEntryId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            if (idempotencyKey != null) {
                Optional<UUID> existing = db.sql("select id from gl.journal_entry where entity_id = ? and idempotency_key = ?")
                        .params(entityId, idempotencyKey).query(UUID.class).optional();
                if (existing.isPresent()) {
                    return new CreateResult(load(entityId, existing.get()), true);
                }
            }
            validateLines(entityId, entity.baseCurrency(), lines, post);
            requireOpenPeriod(entityId, entryDate);

            UUID id = Ids.newId();
            db.sql("""
                    insert into gl.journal_entry (id, org_id, entity_id, entry_date, memo, source, source_ref, status,
                                                  reverses_entry_id, idempotency_key)
                    values (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?)""")
                    .params(id, orgId, entityId, entryDate, memo, source, sourceRef, reversesEntryId, idempotencyKey)
                    .update();
            int lineNo = 1;
            for (NewLine line : lines) {
                db.sql("""
                        insert into gl.journal_line (id, org_id, journal_entry_id, line_no, account_id, amount_minor, currency, memo)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(Ids.newId(), orgId, id, lineNo++, line.accountId(), line.amount().minorUnits(),
                                line.amount().currency(), line.memo())
                        .update();
            }
            if (post) {
                markPosted(entityId, id);
            }
            return new CreateResult(load(entityId, id), false);
        });
    }

    public JournalEntry post(UUID orgId, UUID entityId, UUID entryId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            JournalEntry entry = lockEntry(entityId, entryId);
            if (entry.status() == JournalEntry.Status.posted) {
                throw new BusinessRuleException("ENTRY_POSTED", "Entry is already posted");
            }
            List<NewLine> lines = entry.lines().stream()
                    .map(l -> new NewLine(l.accountId(), l.amount(), l.memo())).toList();
            validateLines(entityId, entity.baseCurrency(), lines, true);
            requireOpenPeriod(entityId, entry.entryDate());
            markPosted(entityId, entryId);
            return load(entityId, entryId);
        });
    }

    public JournalEntry reverse(UUID orgId, UUID entityId, UUID entryId, LocalDate reversalDate, String memo) {
        orgs.getEntity(orgId, entityId);
        JournalEntry original = orgScope.call(orgId, () -> lockEntry(entityId, entryId));
        if (original.status() != JournalEntry.Status.posted) {
            throw new BusinessRuleException("NOT_POSTED", "Only posted entries can be reversed; delete the draft instead");
        }
        boolean alreadyReversed = orgScope.call(orgId, () -> db.sql(
                        "select exists (select 1 from gl.journal_entry where reverses_entry_id = ?)")
                .param(entryId).query(Boolean.class).single());
        if (alreadyReversed) {
            throw new BusinessRuleException("ALREADY_REVERSED", "Entry has already been reversed");
        }
        List<NewLine> negated = original.lines().stream()
                .map(l -> new NewLine(l.accountId(), l.amount().negate(), l.memo())).toList();
        String reversalMemo = memo != null ? memo
                : "Reversal of " + original.entryDate() + (original.memo() == null ? "" : ": " + original.memo());
        return create(orgId, entityId, reversalDate != null ? reversalDate : original.entryDate(), truncate(reversalMemo),
                true, negated, null, "reversal", null, entryId).entry();
    }

    public void deleteDraft(UUID orgId, UUID entityId, UUID entryId) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> {
            JournalEntry entry = lockEntry(entityId, entryId);
            if (entry.status() == JournalEntry.Status.posted) {
                throw new BusinessRuleException("ENTRY_POSTED", "Posted entries can't be deleted; reverse it instead");
            }
            db.sql("delete from gl.journal_entry where id = ?").param(entryId).update();
        });
    }

    public JournalEntry get(UUID orgId, UUID entityId, UUID entryId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, entryId));
    }

    public List<JournalEntry> list(UUID orgId, UUID entityId, LocalDate from, LocalDate to, JournalEntry.Status status) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            List<UUID> ids = db.sql("""
                    select id from gl.journal_entry
                    where entity_id = :entity
                      and (cast(:from as date) is null or entry_date >= cast(:from as date))
                      and (cast(:to as date) is null or entry_date <= cast(:to as date))
                      and (cast(:status as text) is null or status = cast(:status as text))
                    order by entry_date, created_at, id
                    limit 1000""")
                    .param("entity", entityId)
                    .param("from", from)
                    .param("to", to)
                    .param("status", status == null ? null : status.name())
                    .query(UUID.class).list();
            List<JournalEntry> result = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                result.add(load(entityId, id));
            }
            return result;
        });
    }

    public Optional<LocalDate> periodLock(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql("select locked_through from gl.period_lock where entity_id = ?")
                .param(entityId).query(LocalDate.class).optional());
    }

    public LocalDate setPeriodLock(UUID orgId, UUID entityId, LocalDate lockedThrough) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> db.sql("""
                insert into gl.period_lock (entity_id, org_id, locked_through) values (?, ?, ?)
                on conflict (entity_id) do update set locked_through = excluded.locked_through, updated_at = now()""")
                .params(entityId, orgId, lockedThrough).update());
        return lockedThrough;
    }

    public ChainVerification verifyChain(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            List<Map<String, Object>> entries = db.sql("""
                    select id, posting_seq, entry_date, memo, prev_hash, hash from gl.journal_entry
                    where entity_id = ? and status = 'posted' order by posting_seq""")
                    .param(entityId).query().listOfRows();
            String expectedPrev = JournalHasher.GENESIS;
            long expectedSeq = 1;
            for (Map<String, Object> e : entries) {
                long seq = ((Number) e.get("posting_seq")).longValue();
                UUID id = (UUID) e.get("id");
                String recomputed = JournalHasher.hash(expectedPrev, seq, id, e.get("entry_date").toString(),
                        (String) e.get("memo"), hashLines(id));
                if (seq != expectedSeq || !expectedPrev.equals(e.get("prev_hash")) || !recomputed.equals(e.get("hash"))) {
                    return new ChainVerification(false, entries.size(), seq);
                }
                expectedPrev = (String) e.get("hash");
                expectedSeq++;
            }
            return new ChainVerification(true, entries.size(), null);
        });
    }

    // ---------- internals (call inside orgScope) ----------

    private void validateLines(UUID entityId, String baseCurrency, List<NewLine> lines, boolean forPosting) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An entry needs at least one line");
        }
        Map<UUID, Account> accounts = new HashMap<>();
        Money total = Money.zero(baseCurrency);
        for (NewLine line : lines) {
            if (line.amount().isZero()) {
                throw new IllegalArgumentException("Line amounts can't be zero");
            }
            if (!line.amount().currency().equals(baseCurrency)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH",
                        "Line currency " + line.amount().currency() + " must match the entity currency " + baseCurrency);
            }
            Account account = accounts.computeIfAbsent(line.accountId(), id -> db.sql("""
                    select id, org_id, entity_id, code, name, type, subtype, parent_id, is_header,
                           default_tax_line_code as tax_line_code, is_archived, created_at
                    from gl.account where id = ?""").param(id).query(Account.class).optional().orElse(null));
            if (account == null || !account.entityId().equals(entityId) || account.isHeader() || account.isArchived()) {
                throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE",
                        "Account " + line.accountId() + " is not an active, non-header account of this entity");
            }
            total = total.add(line.amount());
        }
        if (forPosting && lines.size() < 2) {
            throw new BusinessRuleException("TOO_FEW_LINES", "A posted entry needs at least two lines");
        }
        if (forPosting && !total.isZero()) {
            throw new BusinessRuleException("UNBALANCED",
                    "Debits and credits differ by " + total.abs().toDecimalString() + " " + baseCurrency);
        }
    }

    private void requireOpenPeriod(UUID entityId, LocalDate date) {
        db.sql("select locked_through from gl.period_lock where entity_id = ?").param(entityId)
                .query(LocalDate.class).optional()
                .filter(lock -> !date.isAfter(lock))
                .ifPresent(lock -> {
                    throw new BusinessRuleException("PERIOD_LOCKED", "Books are locked through " + lock);
                });
    }

    private void markPosted(UUID entityId, UUID entryId) {
        // Serialize posting per entity so sequence numbers and the hash chain have no gaps or forks.
        db.sql("select id from org.entity where id = ? for update").param(entityId).query(UUID.class).single();
        Map<String, Object> last = db.sql("""
                select posting_seq, hash from gl.journal_entry
                where entity_id = ? and status = 'posted' order by posting_seq desc limit 1""")
                .param(entityId).query().listOfRows().stream().findFirst().orElse(null);
        long seq = last == null ? 1 : ((Number) last.get("posting_seq")).longValue() + 1;
        String prevHash = last == null ? JournalHasher.GENESIS : (String) last.get("hash");

        Map<String, Object> entry = db.sql("select entry_date, memo from gl.journal_entry where id = ?")
                .param(entryId).query().singleRow();
        String hash = JournalHasher.hash(prevHash, seq, entryId, entry.get("entry_date").toString(),
                (String) entry.get("memo"), hashLines(entryId));
        db.sql("""
                update gl.journal_entry set status = 'posted', posting_seq = ?, prev_hash = ?, hash = ?, posted_at = now()
                where id = ?""").params(seq, prevHash, hash, entryId).update();
    }

    private List<JournalHasher.HashLine> hashLines(UUID entryId) {
        return db.sql("select line_no, account_id, amount_minor, currency from gl.journal_line where journal_entry_id = ?")
                .param(entryId)
                .query((rs, n) -> new JournalHasher.HashLine(rs.getInt("line_no"), rs.getObject("account_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("currency")))
                .list();
    }

    private JournalEntry lockEntry(UUID entityId, UUID entryId) {
        db.sql("select id from gl.journal_entry where entity_id = ? and id = ? for update")
                .params(entityId, entryId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Journal entry " + entryId + " not found"));
        return load(entityId, entryId);
    }

    private JournalEntry load(UUID entityId, UUID entryId) {
        List<JournalEntry.Line> lines = db.sql("""
                select line_no, account_id, amount_minor, currency, memo from gl.journal_line
                where journal_entry_id = ? order by line_no""")
                .param(entryId)
                .query((rs, n) -> new JournalEntry.Line(rs.getInt("line_no"), rs.getObject("account_id", UUID.class),
                        Money.ofMinor(rs.getLong("amount_minor"), rs.getString("currency")), rs.getString("memo")))
                .list();
        return db.sql("""
                select id, org_id, entity_id, entry_date, memo, source, status, reverses_entry_id, posting_seq, hash,
                       posted_at, created_at
                from gl.journal_entry where entity_id = ? and id = ?""")
                .params(entityId, entryId)
                .query((rs, n) -> new JournalEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getObject("entity_id", UUID.class),
                        rs.getObject("entry_date", LocalDate.class),
                        rs.getString("memo"),
                        rs.getString("source"),
                        JournalEntry.Status.valueOf(rs.getString("status")),
                        rs.getObject("reverses_entry_id", UUID.class),
                        (Long) rs.getObject("posting_seq"),
                        rs.getString("hash"),
                        rs.getObject("posted_at", java.time.OffsetDateTime.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        lines))
                .optional()
                .orElseThrow(() -> new NotFoundException("Journal entry " + entryId + " not found"));
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
