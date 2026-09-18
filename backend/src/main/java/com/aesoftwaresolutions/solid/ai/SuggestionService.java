package com.aesoftwaresolutions.solid.ai;

import com.aesoftwaresolutions.solid.bank.BankModels;
import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.AccountType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Asks the local model which account a bank description probably belongs to.
 *
 * <p>What is sent: the description, the amount, and the entity's own income and expense account codes and names.
 * Nothing else — no legal name, no customer or vendor names, no balances. What comes back is only ever used to
 * pick from that list; the model cannot name an account that does not exist, and nothing is posted or saved.
 * Tax figures never come from a model (see CLAUDE.md).
 */
@Service
public class SuggestionService {

    public record Suggestion(UUID accountId, String code, String name, String source, String model, String reason) {

        static Suggestion none(String reason) {
            return new Suggestion(null, null, null, null, null, reason);
        }
    }

    private final LocalModel model;
    private final BankService bank;
    private final AccountService accounts;

    SuggestionService(LocalModel model, BankService bank, AccountService accounts) {
        this.model = model;
        this.bank = bank;
        this.accounts = accounts;
    }

    public Suggestion suggestCategory(UUID orgId, UUID entityId, UUID txnId) {
        BankModels.BankTransaction txn = bank.getTransaction(orgId, entityId, txnId);
        if (!model.enabled()) {
            return Suggestion.none("Category suggestions are turned off on this server (solid.ai.enabled).");
        }
        List<Account> candidates = accounts.list(orgId, entityId).stream()
                .filter(a -> !a.isHeader() && !a.isArchived())
                .filter(a -> a.type() == AccountType.income || a.type() == AccountType.expense)
                .toList();
        if (candidates.isEmpty()) {
            return Suggestion.none("This entity has no income or expense accounts to choose from.");
        }

        Optional<JsonNode> answer = model.askForJson(prompt(txn, candidates));
        if (answer.isEmpty()) {
            return Suggestion.none("The local model did not answer; category suggestions are unavailable.");
        }
        String code = answer.get().path("code").asText("").trim();
        if (code.isEmpty() || code.equalsIgnoreCase("unknown") || code.equalsIgnoreCase("null")) {
            return Suggestion.none("The model had no confident suggestion.");
        }
        return candidates.stream().filter(a -> a.code().equals(code)).findFirst()
                .map(account -> new Suggestion(account.id(), account.code(), account.name(), "ai", model.model(),
                        null))
                .orElseGet(() -> Suggestion.none("The model answered with \"" + code
                        + "\", which is not one of this entity's accounts, so it was ignored."));
    }

    /** The whole prompt, so what leaves the process is visible in one place. */
    private static String prompt(BankModels.BankTransaction txn, List<Account> candidates) {
        StringBuilder list = new StringBuilder();
        for (Account account : candidates) {
            list.append(account.code()).append(" = ").append(account.name()).append('\n');
        }
        return """
                You are helping categorise a bookkeeping transaction.

                Bank description: %s
                Amount: %s %s (negative means money left the account)

                Choose the single best account from this list. Answer with JSON only, in the form {"code": "6220"}.
                If nothing fits, answer {"code": "unknown"}. Never invent a code that is not listed.

                %s""".formatted(txn.description(), txn.amount().toDecimalString(), txn.amount().currency(), list);
    }
}
