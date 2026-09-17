package com.aesoftwaresolutions.solid.tax;

/**
 * One line on a tax form that book amounts can roll up to.
 *
 * @param code  stable code, e.g. {@code F1040.SCH_C.L8}
 * @param form  form code, e.g. {@code F1040.SCH_C}
 * @param line  line id on the form, e.g. {@code L8}
 * @param label human-readable label
 * @param kind  income, expense or cogs
 */
public record TaxLine(String code, String form, String line, String label, Kind kind) {

    public enum Kind { income, expense, cogs }
}
