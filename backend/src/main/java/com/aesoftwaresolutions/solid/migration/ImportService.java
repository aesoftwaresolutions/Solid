package com.aesoftwaresolutions.solid.migration;

import com.aesoftwaresolutions.solid.billing.BillingModels;
import com.aesoftwaresolutions.solid.billing.BillingService;
import com.aesoftwaresolutions.solid.billing.PayableModels;
import com.aesoftwaresolutions.solid.billing.PayableService;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.AccountType;
import com.aesoftwaresolutions.solid.migration.ImportModels.Action;
import com.aesoftwaresolutions.solid.migration.ImportModels.ImportResult;
import com.aesoftwaresolutions.solid.migration.ImportModels.Kind;
import com.aesoftwaresolutions.solid.migration.ImportModels.ResultRow;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import com.aesoftwaresolutions.solid.tax.TaxLineCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Imports a chart of accounts, a customer list or a vendor list from CSV.
 *
 * <p>Two passes over the same file. The first works out what each row would do and collects every problem with
 * its line number; the second does it, and only if the first found nothing wrong. Writes go through the normal
 * services, so an imported account obeys the same rules as one typed into the screen, and the whole commit runs
 * in one transaction — a file either arrives complete or not at all.
 */
@Service
public class ImportService {

    static final int MAX_ROWS = 2000;

    private final OrgScope orgScope;
    private final OrgService orgs;
    private final AccountService accounts;
    private final BillingService billing;
    private final PayableService payables;
    private final TaxLineCatalog taxLines;

    ImportService(OrgScope orgScope, OrgService orgs, AccountService accounts, BillingService billing,
                  PayableService payables, TaxLineCatalog taxLines) {
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.accounts = accounts;
        this.billing = billing;
        this.payables = payables;
        this.taxLines = taxLines;
    }

    public ImportResult preview(UUID orgId, UUID entityId, Kind kind, String csv) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> plan(orgId, entityId, kind, csv).result(false));
    }

    public ImportResult commit(UUID orgId, UUID entityId, Kind kind, String csv) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            Plan plan = plan(orgId, entityId, kind, csv);
            if (!plan.ready()) {
                // Nothing has been written yet, and nothing will be: the caller gets the same report as a preview.
                return plan.result(false);
            }
            plan.writes().forEach(write -> write.accept(plan));
            return plan.result(true);
        });
    }

    // ---------------- planning ----------------

    /** The plan for one file: a report row per line, plus the writes to run if every row is usable. */
    private static final class Plan {
        private final Kind kind;
        private final List<ResultRow> rows = new ArrayList<>();
        private final List<Consumer<Plan>> writes = new ArrayList<>();
        private final List<String> ignoredColumns;
        /** Account ids by code — seeded with what is already there and added to as rows are created. */
        private final Map<String, UUID> accountIdsByCode = new HashMap<>();

        Plan(Kind kind, List<String> ignoredColumns) {
            this.kind = kind;
            this.ignoredColumns = ignoredColumns;
        }

        void ok(int line, String key, Action action, String detail, Consumer<Plan> write) {
            rows.add(new ResultRow(line, key, action, detail));
            if (write != null) {
                writes.add(write);
            }
        }

        void problem(int line, String key, String detail) {
            rows.add(new ResultRow(line, key, Action.error, detail));
        }

        List<Consumer<Plan>> writes() {
            return writes;
        }

        boolean ready() {
            return rows.stream().noneMatch(r -> r.action() == Action.error);
        }

        ImportResult result(boolean committed) {
            int create = (int) rows.stream().filter(r -> r.action() == Action.create).count();
            int skip = (int) rows.stream().filter(r -> r.action() == Action.skip).count();
            int problems = (int) rows.stream().filter(r -> r.action() == Action.error).count();
            return new ImportResult(kind, committed, rows.size(), create, skip, problems, problems == 0,
                    ignoredColumns, List.copyOf(rows));
        }
    }

    private Plan plan(UUID orgId, UUID entityId, Kind kind, String csv) {
        CsvTable table = CsvTable.parse(csv == null ? "" : csv, MAX_ROWS);
        return switch (kind) {
            case accounts -> planAccounts(orgId, entityId, table);
            case customers -> planCustomers(orgId, entityId, table);
            case vendors -> planVendors(orgId, entityId, table);
        };
    }

    private static final List<String> ACCOUNT_COLUMNS =
            List.of("code", "name", "type", "subtype", "parent", "header", "tax line");
    private static final List<String> CUSTOMER_COLUMNS =
            List.of("name", "email", "phone", "billing address", "notes");
    private static final List<String> VENDOR_COLUMNS =
            List.of("name", "email", "phone", "address", "tax classification", "1099", "default expense account");

    private Plan planAccounts(UUID orgId, UUID entityId, CsvTable table) {
        Plan plan = new Plan(Kind.accounts, table.ignoredColumns(ACCOUNT_COLUMNS));
        requireColumns(table, "code", "name", "type");

        Map<String, Account> existing = new LinkedHashMap<>();
        for (Account account : accounts.list(orgId, entityId)) {
            existing.put(account.code().toLowerCase(Locale.ROOT), account);
            plan.accountIdsByCode.put(account.code().toLowerCase(Locale.ROOT), account.id());
        }
        // Rows planned so far, so a parent named earlier in the same file can be checked and later linked.
        record Planned(AccountType type, boolean header) {
        }
        Map<String, Planned> inFile = new LinkedHashMap<>();

        for (CsvTable.Row row : table.rows()) {
            String code = row.get("code");
            String name = row.get("name");
            String key = code.isEmpty() ? name : code;
            if (code.isEmpty() || name.isEmpty()) {
                plan.problem(row.line(), key, "A code and a name are both required");
                continue;
            }
            String lower = code.toLowerCase(Locale.ROOT);
            if (inFile.containsKey(lower)) {
                plan.problem(row.line(), key, "Code " + code + " appears more than once in this file");
                continue;
            }
            AccountType type;
            try {
                type = AccountType.valueOf(row.get("type").trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plan.problem(row.line(), key,
                        "Type '" + row.get("type") + "' is not one of asset, liability, equity, income, expense");
                continue;
            }
            Boolean header = bool(row.get("header"), false);
            if (header == null) {
                plan.problem(row.line(), key, "Header '" + row.get("header") + "' should be yes or no");
                continue;
            }
            String taxLine = blankToNull(row.get("tax line"));
            if (taxLine != null && taxLines.find(taxLine).isEmpty()) {
                plan.problem(row.line(), key, "Tax line '" + taxLine + "' is not a line we know");
                continue;
            }
            String parentCode = blankToNull(row.get("parent"));
            if (parentCode != null) {
                String parentLower = parentCode.toLowerCase(Locale.ROOT);
                Account parentOnFile = existing.get(parentLower);
                Planned parentInFile = inFile.get(parentLower);
                if (parentOnFile == null && parentInFile == null) {
                    plan.problem(row.line(), key, "Parent " + parentCode
                            + " is not in the books and does not appear earlier in this file");
                    continue;
                }
                AccountType parentType = parentOnFile != null ? parentOnFile.type() : parentInFile.type();
                boolean parentHeader = parentOnFile != null ? parentOnFile.isHeader() : parentInFile.header();
                if (!parentHeader || parentType != type) {
                    plan.problem(row.line(), key, "Parent " + parentCode + " must be a header account of type "
                            + type + " (it is " + (parentHeader ? "" : "not a header, ") + parentType + ")");
                    continue;
                }
            }
            if (existing.containsKey(lower)) {
                inFile.put(lower, new Planned(type, header));
                plan.ok(row.line(), code, Action.skip, "Account " + code + " is already in the books", null);
                continue;
            }
            inFile.put(lower, new Planned(type, header));
            String subtype = blankToNull(row.get("subtype"));
            boolean isHeader = header;
            plan.ok(row.line(), code, Action.create, name + " (" + type + ")", p -> {
                UUID parentId = parentCode == null ? null : p.accountIdsByCode.get(parentCode.toLowerCase(Locale.ROOT));
                Account created = accounts.create(orgId, entityId, code, name, type, subtype, parentId, isHeader,
                        taxLine);
                p.accountIdsByCode.put(lower, created.id());
            });
        }
        return plan;
    }

    private Plan planCustomers(UUID orgId, UUID entityId, CsvTable table) {
        Plan plan = new Plan(Kind.customers, table.ignoredColumns(CUSTOMER_COLUMNS));
        requireColumns(table, "name");

        Map<String, BillingModels.Customer> existing = new LinkedHashMap<>();
        billing.listCustomers(orgId, entityId).forEach(c -> existing.put(key(c.name()), c));
        Map<String, Integer> inFile = new LinkedHashMap<>();

        for (CsvTable.Row row : table.rows()) {
            String name = row.get("name");
            if (name.isEmpty()) {
                plan.problem(row.line(), "", "A name is required");
                continue;
            }
            if (inFile.containsKey(key(name))) {
                plan.problem(row.line(), name,
                        "'" + name + "' also appears on line " + inFile.get(key(name)) + " of this file");
                continue;
            }
            inFile.put(key(name), row.line());
            if (existing.containsKey(key(name))) {
                plan.ok(row.line(), name, Action.skip, "'" + name + "' is already a customer", null);
                continue;
            }
            String email = blankToNull(row.get("email"));
            String phone = blankToNull(row.get("phone"));
            String address = blankToNull(row.get("billing address"));
            String notes = blankToNull(row.get("notes"));
            plan.ok(row.line(), name, Action.create, "Will be added as a customer",
                    p -> billing.createCustomer(orgId, entityId, name, email, phone, address, notes));
        }
        return plan;
    }

    private Plan planVendors(UUID orgId, UUID entityId, CsvTable table) {
        Plan plan = new Plan(Kind.vendors, table.ignoredColumns(VENDOR_COLUMNS));
        requireColumns(table, "name");

        Map<String, PayableModels.Vendor> existing = new LinkedHashMap<>();
        payables.listVendors(orgId, entityId).forEach(v -> existing.put(key(v.name()), v));
        Map<String, Account> accountsByCode = new LinkedHashMap<>();
        accounts.list(orgId, entityId).forEach(a -> accountsByCode.put(a.code().toLowerCase(Locale.ROOT), a));
        Map<String, Integer> inFile = new LinkedHashMap<>();

        for (CsvTable.Row row : table.rows()) {
            String name = row.get("name");
            if (name.isEmpty()) {
                plan.problem(row.line(), "", "A name is required");
                continue;
            }
            if (inFile.containsKey(key(name))) {
                plan.problem(row.line(), name,
                        "'" + name + "' also appears on line " + inFile.get(key(name)) + " of this file");
                continue;
            }
            inFile.put(key(name), row.line());
            Boolean is1099 = bool(row.get("1099"), false);
            if (is1099 == null) {
                plan.problem(row.line(), name, "1099 '" + row.get("1099") + "' should be yes or no");
                continue;
            }
            String expenseCode = blankToNull(row.get("default expense account"));
            Account expenseAccount = null;
            if (expenseCode != null) {
                expenseAccount = accountsByCode.get(expenseCode.toLowerCase(Locale.ROOT));
                if (expenseAccount == null) {
                    plan.problem(row.line(), name, "There is no account with code " + expenseCode);
                    continue;
                }
                if (expenseAccount.type() != AccountType.expense || expenseAccount.isHeader()) {
                    plan.problem(row.line(), name,
                            "Account " + expenseCode + " is not an expense account you can post to");
                    continue;
                }
            }
            if (existing.containsKey(key(name))) {
                plan.ok(row.line(), name, Action.skip, "'" + name + "' is already a vendor", null);
                continue;
            }
            String email = blankToNull(row.get("email"));
            String phone = blankToNull(row.get("phone"));
            String address = blankToNull(row.get("address"));
            String classification = blankToNull(row.get("tax classification"));
            boolean flag = is1099;
            UUID expenseAccountId = expenseAccount == null ? null : expenseAccount.id();
            plan.ok(row.line(), name, Action.create, flag ? "Will be added as a 1099 vendor" : "Will be added as a vendor",
                    p -> payables.createVendor(orgId, entityId, name, email, phone, address, null, classification,
                            flag, expenseAccountId));
        }
        return plan;
    }

    // ---------------- small helpers ----------------

    private static void requireColumns(CsvTable table, String... required) {
        List<String> missing = new ArrayList<>();
        for (String column : required) {
            if (!table.has(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new com.aesoftwaresolutions.solid.common.BusinessRuleException("IMPORT_MISSING_COLUMNS",
                    "The file needs a column for: " + String.join(", ", missing)
                            + " (it has: " + String.join(", ", table.columns()) + ")");
        }
    }

    /** Reads yes/no in the several spellings people actually use. Returns null when the text is none of them. */
    private static Boolean bool(String text, boolean whenBlank) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return whenBlank;
        }
        return switch (t) {
            case "y", "yes", "true", "1", "x" -> Boolean.TRUE;
            case "n", "no", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
