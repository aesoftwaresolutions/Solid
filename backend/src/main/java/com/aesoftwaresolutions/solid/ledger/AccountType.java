package com.aesoftwaresolutions.solid.ledger;

/** The five classic account types. Income and expense accounts close to equity at year end. */
public enum AccountType {
    asset, liability, equity, income, expense;

    public boolean isIncomeStatement() {
        return this == income || this == expense;
    }
}
