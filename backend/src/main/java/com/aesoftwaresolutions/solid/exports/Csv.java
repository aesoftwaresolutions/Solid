package com.aesoftwaresolutions.solid.exports;

import java.util.List;

/**
 * Writes CSV that a spreadsheet opens safely.
 *
 * <p>Two rules matter here. Fields with commas, quotes or newlines are quoted (RFC 4180), and a field that starts
 * with {@code = + - @} is prefixed with an apostrophe, because otherwise a description someone typed — or a payee
 * name that arrived in a bank file — would be run as a formula when the export is opened.
 */
final class Csv {

    private Csv() {
    }

    static String row(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(field(values[i]));
        }
        return out.append('\n').toString();
    }

    static String row(List<?> values) {
        return row(values.toArray());
    }

    private static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        return needsQuotes ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
