package com.aesoftwaresolutions.solid.common;

/**
 * Writes one CSV cell safely. Every CSV Solid produces goes through here (spec 065, row 9).
 *
 * <p>Two jobs. Quoting, so a comma or quote in a name does not shift the columns. And neutralising formulas: a
 * spreadsheet runs a cell that starts with {@code = + - @} (or a tab or carriage return) as a formula, so an
 * account or customer named {@code =HYPERLINK(...)} would become a live link, or worse, in whoever opens the
 * export. Such a cell gets a leading apostrophe, which spreadsheets read as "this is text". A plain number —
 * {@code -12.50} — is left alone, or every credit in the file would stop adding up.
 */
public final class CsvCells {

    private CsvCells() {
    }

    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        boolean looksLikeFormula = !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0
                && !text.matches("-?\\d+(\\.\\d+)?");
        if (looksLikeFormula) {
            text = "'" + text;
        }
        boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        return needsQuotes ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    /** One line of CSV, newline included. */
    public static String row(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        return line.append('\n').toString();
    }
}
