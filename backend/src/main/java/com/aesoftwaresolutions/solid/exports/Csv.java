package com.aesoftwaresolutions.solid.exports;

import com.aesoftwaresolutions.solid.common.CsvCells;
import java.util.List;

/**
 * Writes CSV that a spreadsheet opens safely. The rules — quoting, and neutralising anything a spreadsheet would
 * run as a formula — live in {@link CsvCells}, shared with every other CSV Solid writes.
 */
final class Csv {

    private Csv() {
    }

    static String row(Object... values) {
        return CsvCells.row(values);
    }

    static String row(List<?> values) {
        return row(values.toArray());
    }
}
