package com.aesoftwaresolutions.solid.migration;

import java.util.List;

/** What an import will do, or has done. */
public final class ImportModels {

    private ImportModels() {
    }

    /** The lists that can be brought in. Money is deliberately not one of them (see spec 040). */
    public enum Kind { accounts, customers, vendors }

    /** What happens to one row of the file. */
    public enum Action {
        /** It will be added. */
        create,
        /** Something with the same key is already there, so the row is left alone. */
        skip,
        /** The row cannot be used; {@code detail} says why. */
        error
    }

    public record ResultRow(int line, String key, Action action, String detail) {
    }

    /**
     * @param committed false for a preview, and false for a commit that was refused
     * @param ready     true when nothing is wrong, so a commit would go through
     */
    public record ImportResult(Kind kind, boolean committed, int totalRows, int created, int skipped,
                               int problemCount, boolean ready, List<String> ignoredColumns, List<ResultRow> rows) {
    }
}
