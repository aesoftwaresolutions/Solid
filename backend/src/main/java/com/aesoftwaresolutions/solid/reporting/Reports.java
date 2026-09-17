package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Report response shapes. */
public final class Reports {

    private Reports() {
    }

    public record TrialBalanceRow(UUID accountId, String code, String name, String type, Money debit, Money credit) {
    }

    public record TrialBalance(LocalDate asOf, String currency, List<TrialBalanceRow> rows, Money totalDebit,
                               Money totalCredit) {
    }

    /** accountId and code are null for computed rows such as Current Year Earnings. */
    public record Row(UUID accountId, String code, String name, Money amount) {
    }

    public record Section(List<Row> rows, Money total) {
    }

    public record ProfitAndLoss(LocalDate from, LocalDate to, String currency, Section income,
                                Section costOfGoodsSold, Money grossProfit, Section expenses, Money netIncome) {
    }

    public record BalanceSheet(LocalDate asOf, String currency, LocalDate fiscalYearStart, Section assets,
                               Section liabilities, Section equity, Money totalLiabilitiesAndEquity,
                               boolean balanced) {
    }
}
