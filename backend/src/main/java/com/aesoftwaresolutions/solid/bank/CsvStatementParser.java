package com.aesoftwaresolutions.solid.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads bank CSV exports. Column names are auto-detected from the header row unless hints are given.
 * Supports either a signed Amount column or separate Debit/Credit columns.
 */
final class CsvStatementParser {

    record Hints(String dateColumn, String descriptionColumn, String amountColumn, String debitColumn,
                 String creditColumn, String dateFormat) {
        static final Hints NONE = new Hints(null, null, null, null, null, null);
    }

    private static final List<String> DATE_NAMES =
            List.of("date", "posted date", "posting date", "transaction date", "trans. date", "trans date");
    private static final List<String> DESC_NAMES = List.of("description", "payee", "name", "merchant", "details", "memo");
    private static final List<String> AMOUNT_NAMES = List.of("amount", "transaction amount", "amount (usd)");
    private static final List<String> DEBIT_NAMES = List.of("debit", "withdrawal", "withdrawals", "money out");
    private static final List<String> CREDIT_NAMES = List.of("credit", "deposit", "deposits", "money in");
    private static final List<String> DATE_FORMATS = List.of("yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "MM/dd/yy", "M/d/yy");

    private CsvStatementParser() {
    }

    static List<ParsedTransaction> parse(String content, Hints hints, int maxRows) {
        List<List<String>> rows = readRows(content);
        if (rows.isEmpty()) {
            throw new StatementParseException(0, "The file is empty");
        }
        List<String> header = rows.get(0).stream()
                .map(h -> h.replace("﻿", "").trim().toLowerCase(Locale.ROOT)).toList();
        int date = column(header, hints.dateColumn(), DATE_NAMES, "date");
        int desc = column(header, hints.descriptionColumn(), DESC_NAMES, "description");
        int amount = optionalColumn(header, hints.amountColumn(), AMOUNT_NAMES);
        int debit = optionalColumn(header, hints.debitColumn(), DEBIT_NAMES);
        int credit = optionalColumn(header, hints.creditColumn(), CREDIT_NAMES);
        if (amount < 0 && (debit < 0 || credit < 0)) {
            throw new StatementParseException(1, "Need an Amount column or both Debit and Credit columns");
        }
        if (rows.size() - 1 > maxRows) {
            throw new StatementParseException(0, "Too many rows (maximum " + maxRows + ")");
        }

        List<DateTimeFormatter> formats = (hints.dateFormat() != null ? List.of(hints.dateFormat()) : DATE_FORMATS)
                .stream().map(DateTimeFormatter::ofPattern).toList();
        List<ParsedTransaction> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int lineNo = i + 1;
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            LocalDate d = parseDate(cell(row, date), formats, lineNo);
            BigDecimal value;
            if (amount >= 0) {
                value = Amounts.parse(cell(row, amount), lineNo);
            } else {
                BigDecimal out = Amounts.parse(cell(row, debit), lineNo);
                BigDecimal in = Amounts.parse(cell(row, credit), lineNo);
                value = (in == null ? BigDecimal.ZERO : in.abs()).subtract(out == null ? BigDecimal.ZERO : out.abs());
            }
            if (value == null || value.signum() == 0) {
                continue; // zero-amount lines (e.g. pending holds) carry no money
            }
            String description = cell(row, desc).trim();
            result.add(new ParsedTransaction(null, d, value, description.isEmpty() ? "(no description)" : description));
        }
        return result;
    }

    private static LocalDate parseDate(String text, List<DateTimeFormatter> formats, int line) {
        String t = text.trim();
        for (DateTimeFormatter f : formats) {
            try {
                LocalDate d = LocalDate.parse(t, f);
                return d.getYear() < 100 ? d.plusYears(2000) : d;
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new StatementParseException(line, "Can't read date '" + text + "'");
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    private static int column(List<String> header, String hint, List<String> names, String label) {
        int idx = optionalColumn(header, hint, names);
        if (idx < 0) {
            throw new StatementParseException(1, "Couldn't find a " + label + " column (found: " + header + ")");
        }
        return idx;
    }

    private static int optionalColumn(List<String> header, String hint, List<String> names) {
        if (hint != null && !hint.isBlank()) {
            int idx = header.indexOf(hint.trim().toLowerCase(Locale.ROOT));
            if (idx < 0) {
                throw new StatementParseException(1, "Column '" + hint + "' not found");
            }
            return idx;
        }
        for (String name : names) {
            int idx = header.indexOf(name);
            if (idx >= 0) {
                return idx;
            }
        }
        return -1;
    }

    /** RFC 4180-style CSV reader: quoted fields, escaped quotes, commas and newlines inside quotes. */
    static List<List<String>> readRows(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                line++;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new StatementParseException(line, "Unclosed quote");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
