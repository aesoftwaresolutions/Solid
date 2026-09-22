package com.aesoftwaresolutions.solid.setup;

import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.JournalEntry;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.ledger.OpeningBalanceService;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What still needs doing before a new set of books is usable.
 *
 * <p>The order matters — a chart of accounts before opening balances, opening balances before the first
 * import — and a new user has no way of knowing it. Everything here is read from the modules that own the
 * records, so this list cannot claim a step is done when the books say otherwise, and asking the question
 * never changes anything.
 */
@Service
public class SetupService {

    public enum Status {
        /** Done, whether it was required or not. */
        done,
        /** Still to do. */
        todo,
        /** Not needed for this entity — but still shown, so nobody wonders whether they missed it. */
        optional
    }

    /** @param where the screen that does this step */
    public record Step(String key, String title, Status status, String detail, String where) {
    }

    public record Setup(boolean complete, int doneCount, int requiredCount, List<Step> steps) {
    }

    private final OrgService orgs;
    private final AccountService accounts;
    private final BankService bank;
    private final JournalService journal;
    private final OpeningBalanceService openingBalances;

    SetupService(OrgService orgs, AccountService accounts, BankService bank, JournalService journal,
                 OpeningBalanceService openingBalances) {
        this.orgs = orgs;
        this.accounts = accounts;
        this.bank = bank;
        this.journal = journal;
        this.openingBalances = openingBalances;
    }

    public Setup setup(UUID orgId, UUID entityId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        List<Account> chart = accounts.list(orgId, entityId);
        boolean hasBankAccount = !bank.listBankAccounts(orgId, entityId).isEmpty();
        boolean hasPostedEntry = !journal.list(orgId, entityId, null, null, JournalEntry.Status.posted).isEmpty();
        boolean hasOpeningBalances = hasOpeningBalances(orgId, entityId);
        long unmapped = chart.stream()
                .filter(a -> !a.isHeader() && !a.isArchived())
                .filter(a -> a.type().isIncomeStatement())
                .filter(a -> a.taxLineCode() == null)
                .count();
        // A household keeps books for itself, not for a Schedule C, so tax lines are not its business.
        boolean household = "individual".equals(entity.kind());

        List<Step> steps = new ArrayList<>();
        steps.add(step("entity_details", "Say where this entity is based",
                entity.homeState() != null && !entity.homeState().isBlank(),
                false,
                "State rules can only ever apply once Solid knows the home state.",
                "Nothing else needs the home state yet, but nothing state-specific can work without it.",
                "settings"));
        steps.add(step("chart_of_accounts", "Set up the chart of accounts",
                !chart.isEmpty(), false,
                chart.size() + " account(s) — the categories everything else is filed under.",
                "Start from a template; you can rename and add accounts afterwards.",
                "accounts"));
        steps.add(step("bank_account", "Add a bank account to import into",
                hasBankAccount, false,
                "There is somewhere for statements to land.",
                "Statements are imported per bank account, so there has to be one first.",
                "bank"));
        steps.add(step("opening_balances", "Enter opening balances",
                hasOpeningBalances, true,
                "The balances you brought in are on the books.",
                "Only if this entity existed before you started using Solid. A new business has nothing to bring in.",
                "accounts"));
        steps.add(step("tax_lines", "Map accounts to tax lines",
                !chart.isEmpty() && unmapped == 0, household,
                "Every income and expense account maps to a line on the return.",
                household
                        ? "A household files no Schedule C, so nothing here has to map to a tax line."
                        : unmapped + " income or expense account(s) still map to nothing, so the tax-line report "
                                + "will leave them out.",
                "accounts"));
        steps.add(step("first_entry", "Record something",
                hasPostedEntry, false,
                "The books have at least one posted entry.",
                "Import a statement or write a journal entry — nothing is real until something is posted.",
                "journal"));

        int required = (int) steps.stream().filter(s -> s.status() != Status.optional).count();
        int done = (int) steps.stream().filter(s -> s.status() == Status.done).count();
        boolean complete = steps.stream().noneMatch(s -> s.status() == Status.todo);
        return new Setup(complete, done, required, List.copyOf(steps));
    }

    /**
     * A step that has been done reports {@code done} whether or not it was required — the person did the work,
     * and calling it "optional" afterwards would be rude.
     */
    private static Step step(String key, String title, boolean done, boolean optional, String doneDetail,
                             String todoDetail, String where) {
        Status status = done ? Status.done : optional ? Status.optional : Status.todo;
        return new Step(key, title, status, done ? doneDetail : todoDetail, where);
    }

    private boolean hasOpeningBalances(UUID orgId, UUID entityId) {
        try {
            openingBalances.get(orgId, entityId);
            return true;
        } catch (NotFoundException e) {
            // No opening balances is a normal state for a business that started here.
            return false;
        }
    }
}
