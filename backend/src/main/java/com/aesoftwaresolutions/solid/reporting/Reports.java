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

    /**
     * One column of the profit and loss by business line (spec 069). {@code businessLineId} null is the
     * "Shared / overhead" column: everything posted without a business line.
     */
    public record BusinessLineColumn(UUID businessLineId, String name, boolean archived, Money income,
                                     Money costOfGoodsSold, Money grossProfit, Money expenses, Money netIncome) {
    }

    /** One income or expense account across the columns. {@code amounts} lines up with the report's columns. */
    public record BusinessLineRow(UUID accountId, String code, String name, String section, List<Money> amounts,
                                  Money total) {
    }

    /** The profit and loss split by business line. The {@code total} column always equals the plain P&amp;L. */
    public record ProfitAndLossByBusinessLine(LocalDate from, LocalDate to, String currency,
                                              List<BusinessLineColumn> columns, List<BusinessLineRow> rows,
                                              BusinessLineColumn total) {
    }

    public record CashFlowSection(List<Row> rows, Money total) {
    }

    /**
     * Cash in and out by where it came from or went to.
     *
     * @param unclassified movements the rules could not place — reported, never folded into operating
     */
    public record CashFlow(LocalDate from, LocalDate to, String currency, Money openingCash,
                           CashFlowSection operating, CashFlowSection investing, CashFlowSection financing,
                           CashFlowSection unclassified, Money netChange, Money closingCash, String note) {
    }

    public record BalanceSheet(LocalDate asOf, String currency, LocalDate fiscalYearStart, Section assets,
                               Section liabilities, Section equity, Money totalLiabilitiesAndEquity,
                               boolean balanced) {
    }
}
