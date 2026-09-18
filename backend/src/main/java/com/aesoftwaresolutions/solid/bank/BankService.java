package com.aesoftwaresolutions.solid.bank;

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
import com.aesoftwaresolutions.solid.platform.RequestContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class BankService {

    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 20_000;

    public record CategorizeItem(UUID transactionId, UUID accountId) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;

    BankService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts, JournalService journal) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
    }

    // ---------------- bank accounts ----------------

    public BankModels.BankAccount createBankAccount(UUID orgId, UUID entityId, String name, UUID glAccountId,
                                                    String institution, String mask) {
        Account gl = accounts.get(orgId, entityId, glAccountId);
        boolean bank = gl.type() == AccountType.asset && "bank".equals(gl.subtype());
        boolean card = gl.type() == AccountType.liability && "credit_card".equals(gl.subtype());
        if (gl.isHeader() || gl.isArchived() || !(bank || card)) {
            throw new BusinessRuleException("INVALID_GL_ACCOUNT",
                    "Link a bank account to an active asset account with subtype 'bank' or a liability with subtype 'credit_card'");
        }
        return orgScope.call(orgId, () -> {
            boolean linked = db.sql("select exists (select 1 from bank.bank_account where gl_account_id = ?)")
                    .param(glAccountId).query(Boolean.class).single();
            if (linked) {
                throw new BusinessRuleException("GL_ACCOUNT_ALREADY_LINKED", "That ledger account already has a bank account");
            }
            UUID id = Ids.newId();
            db.sql("""
                    insert into bank.bank_account (id, org_id, entity_id, gl_account_id, name, institution, mask)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, glAccountId, name.trim(), institution, mask).update();
            return findBankAccount(entityId, id);
        });
    }

    public List<BankModels.BankAccount> listBankAccounts(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(BANK_ACCOUNT_SELECT + " where entity_id = ? order by name")
                .param(entityId).query(BankModels.BankAccount.class).list());
    }

    // ---------------- import ----------------

    public BankModels.ImportResult importStatement(UUID orgId, UUID entityId, UUID bankAccountId, String filename,
                                                   byte[] bytes, CsvStatementParser.Hints hints) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The file is empty");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large (maximum 5 MB)");
        }
        String content = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        boolean ofx = OfxStatementParser.looksLikeOfx(content);
        List<ParsedTransaction> parsed = ofx
                ? OfxStatementParser.parse(content, MAX_ROWS)
                : CsvStatementParser.parse(content, hints == null ? CsvStatementParser.Hints.NONE : hints, MAX_ROWS);

        return orgScope.call(orgId, () -> {
            findBankAccount(entityId, bankAccountId);
            UUID batchId = Ids.newId();
            db.sql("""
                    insert into bank.import_batch (id, org_id, bank_account_id, filename, format, parsed_count, imported_count,
                                                   duplicate_count, created_by)
                    values (?, ?, ?, ?, ?, ?, 0, 0, ?)""")
                    .params(batchId, orgId, bankAccountId, truncate(filename, 255), ofx ? "ofx" : "csv", parsed.size(),
                            RequestContext.current().map(RequestContext.Caller::userId).orElse(null))
                    .update();

            Map<String, Integer> occurrences = new HashMap<>();
            int imported = 0;
            for (ParsedTransaction t : parsed) {
                Money amount = Money.of(t.amount(), entity.baseCurrency(), RoundingMode.UNNECESSARY);
                String normalized = normalize(t.description());
                String externalId;
                if (t.fitId() != null) {
                    externalId = "fitid:" + t.fitId();
                } else {
                    String key = t.date() + "|" + amount.minorUnits() + "|" + normalized;
                    int occurrence = occurrences.merge(key, 1, Integer::sum);
                    externalId = "csv:" + sha256(key + "|" + occurrence);
                }
                int inserted = db.sql("""
                        insert into bank.bank_txn (id, org_id, bank_account_id, import_batch_id, external_id, posted_date,
                                                   amount_minor, currency, description, normalized_description)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (bank_account_id, external_id) do nothing""")
                        .params(Ids.newId(), orgId, bankAccountId, batchId, truncate(externalId, 255), t.date(),
                                amount.minorUnits(), amount.currency(), truncate(t.description(), 500), normalized)
                        .update();
                imported += inserted;
            }
            int duplicates = parsed.size() - imported;
            db.sql("update bank.import_batch set imported_count = ?, duplicate_count = ? where id = ?")
                    .params(imported, duplicates, batchId).update();
            refreshSuggestions(entityId);
            return new BankModels.ImportResult(batchId, ofx ? "ofx" : "csv", parsed.size(), imported, duplicates);
        });
    }

    // ---------------- review queue ----------------

    public List<BankModels.BankTransaction> listTransactions(UUID orgId, UUID entityId, String status, UUID bankAccountId) {
        orgs.getEntity(orgId, entityId);
        if (status != null && !List.of("new", "categorized", "excluded").contains(status)) {
            throw new IllegalArgumentException("status must be new, categorized or excluded");
        }
        return orgScope.call(orgId, () -> db.sql(TXN_SELECT + "\n" + """
                where a.entity_id = :entity
                   and (cast(:status as text) is null or t.status = cast(:status as text))
                   and (cast(:account as uuid) is null or t.bank_account_id = cast(:account as uuid))
                 order by t.posted_date, t.created_at, t.id
                 limit 2000""")
                .param("entity", entityId).param("status", status).param("account", bankAccountId)
                .query(this::mapTxn).list());
    }

    /** How many imported transactions still need a category (used by the tax-line readiness check). */
    public int countUncategorized(UUID orgId, UUID entityId) {
        return orgScope.call(orgId, () -> db.sql("""
                select count(*) from bank.bank_txn t join bank.bank_account a on a.id = t.bank_account_id
                where a.entity_id = ? and t.status = 'new'""").param(entityId).query(Integer.class).single());
    }

    /** One transaction, for screens and other modules (the suggestion endpoint, for example). */
    public BankModels.BankTransaction getTransaction(UUID orgId, UUID entityId, UUID txnId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> findTxn(entityId, txnId));
    }

    public BankModels.BankTransaction categorize(UUID orgId, UUID entityId, UUID txnId, UUID accountId, String memo) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> categorizeInScope(orgId, entityId, txnId, accountId, memo));
    }

    /** All-or-nothing: one failure rolls back every item. */
    public List<BankModels.BankTransaction> categorizeAll(UUID orgId, UUID entityId, List<CategorizeItem> items) {
        orgs.getEntity(orgId, entityId);
        if (items.isEmpty() || items.size() > 500) {
            throw new IllegalArgumentException("Send between 1 and 500 items");
        }
        return orgScope.call(orgId, () -> items.stream()
                .map(i -> categorizeInScope(orgId, entityId, i.transactionId(), i.accountId(), null)).toList());
    }

    public BankModels.BankTransaction exclude(UUID orgId, UUID entityId, UUID txnId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BankModels.BankTransaction txn = lockTxn(entityId, txnId);
            if (!txn.status().equals("new")) {
                throw new BusinessRuleException("NOT_NEW", "Only new transactions can be excluded");
            }
            db.sql("update bank.bank_txn set status = 'excluded' where id = ?").param(txnId).update();
            return findTxn(entityId, txnId);
        });
    }

    public BankModels.BankTransaction uncategorize(UUID orgId, UUID entityId, UUID txnId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            BankModels.BankTransaction txn = lockTxn(entityId, txnId);
            if (txn.status().equals("excluded")) {
                db.sql("update bank.bank_txn set status = 'new' where id = ?").param(txnId).update();
                return findTxn(entityId, txnId);
            }
            if (!txn.status().equals("categorized")) {
                throw new BusinessRuleException("NOT_CATEGORIZED", "Transaction is not categorized");
            }
            journal.reverse(orgId, entityId, txn.journalEntryId(), null, "Uncategorized bank transaction");
            db.sql("update bank.bank_txn set status = 'new', journal_entry_id = null, category_account_id = null where id = ?")
                    .param(txnId).update();
            return findTxn(entityId, txnId);
        });
    }

    // ---------------- rules ----------------

    public BankModels.Rule createRule(UUID orgId, UUID entityId, String contains, UUID accountId, Integer priority) {
        Account account = accounts.get(orgId, entityId, accountId);
        if (account.isHeader() || account.isArchived()) {
            throw new BusinessRuleException("ACCOUNT_NOT_POSTABLE", "Rules must point to an active, non-header account");
        }
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("insert into bank.categorization_rule (id, org_id, entity_id, contains, account_id, priority) values (?, ?, ?, ?, ?, ?)")
                    .params(id, orgId, entityId, contains.trim(), accountId, priority == null ? 100 : priority).update();
            refreshSuggestions(entityId);
            return db.sql(RULE_SELECT + " where id = ?").param(id).query(BankModels.Rule.class).single();
        });
    }

    public List<BankModels.Rule> listRules(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(RULE_SELECT + " where entity_id = ? order by priority, created_at")
                .param(entityId).query(BankModels.Rule.class).list());
    }

    public void deleteRule(UUID orgId, UUID entityId, UUID ruleId) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> {
            int deleted = db.sql("delete from bank.categorization_rule where entity_id = ? and id = ?")
                    .params(entityId, ruleId).update();
            if (deleted == 0) {
                throw new NotFoundException("Rule " + ruleId + " not found");
            }
            refreshSuggestions(entityId);
        });
    }

    // ---------------- internals (inside org scope) ----------------

    private BankModels.BankTransaction categorizeInScope(UUID orgId, UUID entityId, UUID txnId, UUID accountId, String memo) {
        BankModels.BankTransaction txn = lockTxn(entityId, txnId);
        if (!txn.status().equals("new")) {
            throw new BusinessRuleException("ALREADY_CATEGORIZED", "Transaction is already " + txn.status());
        }
        BankModels.BankAccount bankAccount = findBankAccount(entityId, txn.bankAccountId());
        if (bankAccount.glAccountId().equals(accountId)) {
            throw new BusinessRuleException("SAME_ACCOUNT", "Pick a category other than the bank account itself");
        }
        String description = memo != null && !memo.isBlank() ? memo : txn.description();
        JournalEntry entry = journal.postFromSource(orgId, entityId, txn.postedDate(), truncate(description, 500), List.of(
                new JournalService.NewLine(bankAccount.glAccountId(), txn.amount(), null),
                new JournalService.NewLine(accountId, txn.amount().negate(), null)), "bank", txnId);
        db.sql("update bank.bank_txn set status = 'categorized', journal_entry_id = ?, category_account_id = ? where id = ?")
                .params(entry.id(), accountId, txnId).update();
        return findTxn(entityId, txnId);
    }

    /** Recomputes suggestions for every uncategorized transaction of the entity. */
    private void refreshSuggestions(UUID entityId) {
        String inEntity = " t.status = 'new' and t.bank_account_id in (select id from bank.bank_account where entity_id = :entity) ";
        String ruleMatch = """
                (select r.account_id from bank.categorization_rule r
                 where r.entity_id = :entity and position(upper(r.contains) in upper(t.description)) > 0
                 order by r.priority, r.created_at limit 1)""";
        String historyMatch = """
                (select c.category_account_id from bank.bank_txn c
                 where c.status = 'categorized' and c.normalized_description = t.normalized_description
                   and c.bank_account_id in (select id from bank.bank_account where entity_id = :entity)
                 order by c.posted_date desc, c.created_at desc limit 1)""";
        // 1. A matching rule wins (lowest priority number first, then the oldest rule).
        // 2. Otherwise reuse the category of the most recent transaction with the same normalized description.
        db.sql("update bank.bank_txn t set suggested_account_id = coalesce(" + ruleMatch + ", " + historyMatch + "),"
                        + " suggestion_source = case when " + ruleMatch + " is not null then 'rule'"
                        + " when " + historyMatch + " is not null then 'history' end"
                        + " where" + inEntity)
                .param("entity", entityId).update();
    }

    private BankModels.BankAccount findBankAccount(UUID entityId, UUID id) {
        return db.sql(BANK_ACCOUNT_SELECT + " where entity_id = ? and id = ?").params(entityId, id)
                .query(BankModels.BankAccount.class).optional()
                .orElseThrow(() -> new NotFoundException("Bank account " + id + " not found"));
    }

    private BankModels.BankTransaction lockTxn(UUID entityId, UUID txnId) {
        db.sql("select t.id from bank.bank_txn t join bank.bank_account a on a.id = t.bank_account_id where a.entity_id = ? and t.id = ? for update of t")
                .params(entityId, txnId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Bank transaction " + txnId + " not found"));
        return findTxn(entityId, txnId);
    }

    private BankModels.BankTransaction findTxn(UUID entityId, UUID txnId) {
        return db.sql(TXN_SELECT + " where a.entity_id = ? and t.id = ?").params(entityId, txnId)
                .query(this::mapTxn).optional()
                .orElseThrow(() -> new NotFoundException("Bank transaction " + txnId + " not found"));
    }

    private BankModels.BankTransaction mapTxn(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new BankModels.BankTransaction(rs.getObject("id", UUID.class), rs.getObject("bank_account_id", UUID.class),
                rs.getObject("posted_date", LocalDate.class), Money.ofMinor(rs.getLong("amount_minor"), rs.getString("currency")),
                rs.getString("description"), rs.getString("status"), rs.getObject("suggested_account_id", UUID.class),
                rs.getString("suggestion_source"), rs.getObject("category_account_id", UUID.class),
                rs.getObject("journal_entry_id", UUID.class));
    }

    /** "ADOBE *CREATIVE CLD 800-833-6687 CA" → "ADOBE CREATIVE CLD CA": drops digits/punctuation that vary per charge. */
    static String normalize(String description) {
        String s = description.toUpperCase(Locale.ROOT).replaceAll("[0-9]+", " ").replaceAll("[^A-Z&]+", " ").trim()
                .replaceAll("\\s+", " ");
        return s.isEmpty() ? "(BLANK)" : s;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static final String BANK_ACCOUNT_SELECT = """
            select id, entity_id, gl_account_id, name, institution, mask, created_at from bank.bank_account""";

    private static final String TXN_SELECT = """
            select t.id, t.bank_account_id, t.posted_date, t.amount_minor, t.currency, t.description, t.status,
                   t.suggested_account_id, t.suggestion_source, t.category_account_id, t.journal_entry_id
            from bank.bank_txn t join bank.bank_account a on a.id = t.bank_account_id""";

    private static final String RULE_SELECT = """
            select id, entity_id, contains, account_id, priority, created_at from bank.categorization_rule""";
}
