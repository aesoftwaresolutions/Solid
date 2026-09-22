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

    /** One data row, remembering the physical line it started on so a problem can be pointed at. */
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

    /** Roughly the biggest a file of {@code maxRows} sensible rows can be; anything larger is not read at all. */
    static final int MAX_BYTES_PER_ROW = 2_000;

    static CsvTable parse(String content, int maxRows) {
        // Checked before a single row is built: a 40 MB paste must be refused, not turned into four million
        // String objects first and rejected afterwards.
        if (content.length() > (long) maxRows * MAX_BYTES_PER_ROW) {
            throw new BusinessRuleException("IMPORT_TOO_LARGE",
                    "The file is too big to import (the limit is about " + (maxRows * MAX_BYTES_PER_ROW / 1_000_000)
                            + " MB and " + maxRows + " rows)");
        }
        // maxRows data rows plus the header, plus one more so that an over-long file can be detected.
        List<Record> raw = readRows(content, maxRows + 2);
        if (raw.isEmpty()) {
            throw new BusinessRuleException("IMPORT_EMPTY", "The file is empty");
        }
        List<String> header = raw.get(0).cells().stream().map(h -> h.replace("﻿", "").trim()).toList();
        if (header.stream().allMatch(String::isBlank)) {
            throw new BusinessRuleException("IMPORT_NO_HEADER", "The first line must name the columns");
        }
        // The reader stops one record past the limit, so this catches an over-long file without having built
        // the whole of it.
        if (raw.size() - 1 > maxRows) {
            throw new BusinessRuleException("IMPORT_TOO_MANY_ROWS",
                    "The file has more than " + maxRows + " rows, which is the limit");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            List<String> cells = raw.get(i).cells();
            Map<String, String> byName = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                byName.put(normalise(header.get(c)), c < cells.size() ? cells.get(c) : "");
            }
            // The physical line, not the record number: a quoted address with a newline in it must not push
            // every later problem one line out of place.
            Row row = new Row(raw.get(i).line(), byName);
            if (!row.isBlank()) {
                rows.add(row);
            }
        }
        return new CsvTable(header, List.copyOf(rows));
    }

    /** One record of the file, and the physical line its first character sat on. */
    private record Record(int line, List<String> cells) {
    }

    /**
     * RFC 4180-style reader: quoted fields, doubled quotes, commas and newlines inside quotes. It stops once
     * {@code maxRecords} records exist, so an over-long file costs only as much as the limit allows.
     */
    private static List<Record> readRows(String content, int maxRecords) {
        List<Record> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordStartLine = 1;
        for (int i = 0; i < content.length() && rows.size() < maxRecords; i++) {
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
                rows.add(new Record(recordStartLine, row));
                row = new ArrayList<>();
                line++;
                recordStartLine = line;
            } else {
                field.append(c);
            }
        }
        if (rows.size() >= maxRecords) {
            // Stopped at the limit: whatever comes after, including an open quote, is the caller's problem
            // to report as "too many rows".
            return rows;
        }
        if (quoted) {
            throw new BusinessRuleException("IMPORT_UNCLOSED_QUOTE", "A quoted value is never closed");
        }
        if ((field.length() > 0 || !row.isEmpty()) && rows.size() < maxRecords) {
            row.add(field.toString());
            rows.add(new Record(recordStartLine, row));
        }
        return rows;
    }
}
