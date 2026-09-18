package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The year's books rolled up to tax form lines, plus what is still missing. */
public record TaxLineReport(
        int taxYear,
        LocalDate from,
        LocalDate to,
        String currency,
        List<Line> lines,
        List<UnmappedAccount> unmapped,
        Totals totals,
        Readiness readiness) {

    public record AccountAmount(UUID accountId, String code, String name, Money amount) {
    }

    public record Line(String code, String form, String line, String label, String kind, Money amount,
                       List<AccountAmount> accounts) {
    }

    public record UnmappedAccount(UUID accountId, String code, String name, String type, Money amount) {
    }

    public record Totals(Money income, Money costOfGoodsSold, Money expenses, Money netProfit) {
    }

    public record Readiness(int draftEntries, int uncategorizedBankTransactions, int unmappedAccounts, boolean ready) {
    }
}
