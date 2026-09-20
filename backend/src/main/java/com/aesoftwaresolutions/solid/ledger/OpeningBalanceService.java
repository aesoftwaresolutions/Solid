package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Turns "here is what I had on the day I started" into one balanced journal entry.
 *
 * <p>People read balances off statements as positive numbers, so that is what this takes: a positive amount is the
 * account's natural balance (a debit for assets and expenses, a credit for liabilities, equity and income). The
 * difference between the two sides is the owner's opening equity, which is what makes the entry balance.
 */
@Service
public class OpeningBalanceService {

    /** The source marker on the journal entry; also how a second attempt is detected. */
    static final String SOURCE = "opening_balance";

    public record NewBalance(UUID accountId, Money amount) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;

    OpeningBalanceService(JdbcClient db, OrgScope orgScope, OrgService orgs, AccountService accounts,
                          JournalService journal) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
    }

    public JournalEntry create(UUID orgId, UUID entityId, LocalDate asOfDate, UUID equityAccountId,
                               List<NewBalance> balances) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();

        List<Account> postable = accounts.list(orgId, entityId).stream()
                .filter(account -> !account.isHeader() && !account.isArchived())
                .toList();
        Account equity = equityAccount(postable, equityAccountId);

        Set<UUID> seen = new HashSet<>();
        List<JournalService.NewLine> lines = new ArrayList<>();
        long plug = 0;
        for (NewBalance balance : balances) {
            if (!balance.amount().currency().equals(ccy)) {
                throw new BusinessRuleException("CURRENCY_MISMATCH", "Amounts must be in " + ccy);
            }
            if (balance.amount().minorUnits() == 0) {
                continue;
            }
            Account account = postable.stream().filter(a -> a.id().equals(balance.accountId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Account " + balance.accountId()
                            + " is not a postable account of this entity"));
            if (!seen.add(account.id())) {
                throw new IllegalArgumentException("Account " + account.code() + " appears twice");
            }
            if (account.id().equals(equity.id())) {
                throw new IllegalArgumentException("The opening-balance equity account is calculated, "
                        + "so it cannot also be given a balance");
            }
            // Positive means the account's natural balance; assets and expenses are debits, the rest credits.
            long signed = naturallyDebit(account.type())
                    ? balance.amount().minorUnits() : -balance.amount().minorUnits();
            lines.add(new JournalService.NewLine(account.id(), Money.ofMinor(signed, ccy),
                    "Opening balance " + account.code()));
            plug += signed;
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Give at least one opening balance");
        }
        if (plug != 0) {
            lines.add(new JournalService.NewLine(equity.id(), Money.ofMinor(-plug, ccy), "Opening equity"));
        }

        List<JournalService.NewLine> toPost = List.copyOf(lines);
        // The check and the posting share one transaction, with the entity row locked, so a double-clicked
        // "Save" cannot post two sets of opening balances — which would silently double the opening equity.
        return orgScope.call(orgId, () -> {
            db.sql("select id from org.entity where id = ? for update").param(entityId).query(UUID.class).single();
            existingInScope(entityId).ifPresent(entry -> {
                throw new BusinessRuleException("OPENING_BALANCES_EXIST",
                        "This entity already has opening balances (entry " + entry.get("id") + " dated "
                                + entry.get("entry_date") + "). Reverse that entry first if the figures were wrong.");
            });
            return journal.postFromSource(orgId, entityId, asOfDate, "Opening balances", toPost, SOURCE, null);
        });
    }

    public JournalEntry get(UUID orgId, UUID entityId) {
        return existing(orgId, entityId)
                .orElseThrow(() -> new NotFoundException("This entity has no opening balances yet"));
    }

    /** The opening-balance entry that still stands: one that exists and has not been reversed. */
    private Optional<JournalEntry> existing(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        Optional<UUID> id = orgScope.call(orgId, () ->
                existingInScope(entityId).map(row -> (UUID) row.get("id")));
        return id.map(value -> journal.get(orgId, entityId, value));
    }

    /** Must run inside {@link OrgScope}; returns the id and date without loading the whole entry. */
    private Optional<java.util.Map<String, Object>> existingInScope(UUID entityId) {
        return db.sql("""
                select e.id, e.entry_date from gl.journal_entry e
                where e.entity_id = ? and e.source = ? and e.status = 'posted'
                  and not exists (select 1 from gl.journal_entry r where r.reverses_entry_id = e.id)
                order by e.entry_date limit 1""")
                .params(entityId, SOURCE).query().listOfRows().stream().findFirst();
    }

    private static boolean naturallyDebit(AccountType type) {
        return type == AccountType.asset || type == AccountType.expense;
    }

    private static Account equityAccount(List<Account> postable, UUID requested) {
        if (requested != null) {
            return postable.stream().filter(a -> a.id().equals(requested) && a.type() == AccountType.equity)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The balancing account must be a postable equity account of this entity"));
        }
        return postable.stream()
                .filter(a -> a.type() == AccountType.equity && "opening_balance".equals(a.subtype()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This chart of accounts has no equity account with "
                        + "subtype 'opening_balance'. Create one (for example \"Opening Balances\") or name an "
                        + "equity account in equityAccountId."));
    }
}
