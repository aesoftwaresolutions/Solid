package com.aesoftwaresolutions.solid.migration;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A CSV file read as a header row plus data rows.
 *
 * <p>Column names are normalised — lower-cased, with spaces, underscores and hyphens removed — so that
 * {@code "Tax Line"}, {@code tax_line} and {@code TAXLINE} are all the same column. A cell is fetched by name
 * rather than by position, because a person exporting from another program has no reason to put the columns
 * in our order.
 */
final class CsvTable {

    /** One data row, remembering the line number it came from so a problem can be pointed at. */
    record Row(int line, Map<String, String> cells) {

        String get(String column) {
            String value = cells.get(normalise(column));
            return value == null ? "" : value.trim();
        }

        boolean isBlank() {
            return cells.values().stream().allMatch(v -> v == null || v.isBlank());
        }
    }

    private final List<String> columns;
    private final List<Row> rows;

    private CsvTable(List<String> columns, List<Row> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    List<String> columns() {
        return columns;
    }

    List<Row> rows() {
        return rows;
    }

    /** Column names in the file that this importer does not use, in the file's own spelling. */
    List<String> ignoredColumns(List<String> known) {
        List<String> knownNames = known.stream().map(CsvTable::normalise).toList();
        return columns.stream().filter(c -> !knownNames.contains(normalise(c))).toList();
    }

    boolean has(String column) {
        return columns.stream().anyMatch(c -> normalise(c).equals(normalise(column)));
    }

    static String normalise(String name) {
        return name == null ? "" : name.replace("﻿", "").toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    static CsvTable parse(String content, int maxRows) {
        List<List<String>> raw = readRows(content);
        if (raw.isEmpty()) {
            throw new BusinessRuleException("IMPORT_EMPTY", "The file is empty");
        }
        List<String> header = raw.get(0).stream().map(h -> h.replace("﻿", "").trim()).toList();
        if (header.stream().allMatch(String::isBlank)) {
            throw new BusinessRuleException("IMPORT_NO_HEADER", "The first line must name the columns");
        }
        // Counted before reading the rows, so an enormous file is turned away rather than half-processed.
        if (raw.size() - 1 > maxRows) {
            throw new BusinessRuleException("IMPORT_TOO_MANY_ROWS",
                    "The file has " + (raw.size() - 1) + " rows; the limit is " + maxRows);
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            List<String> cells = raw.get(i);
            Map<String, String> byName = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                byName.put(normalise(header.get(c)), c < cells.size() ? cells.get(c) : "");
            }
            Row row = new Row(i + 1, byName);
            if (!row.isBlank()) {
                rows.add(row);
            }
        }
        return new CsvTable(header, List.copyOf(rows));
    }

    /** RFC 4180-style reader: quoted fields, doubled quotes, commas and newlines inside quotes. */
    private static List<List<String>> readRows(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
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
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new BusinessRuleException("IMPORT_UNCLOSED_QUOTE", "A quoted value is never closed");
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
