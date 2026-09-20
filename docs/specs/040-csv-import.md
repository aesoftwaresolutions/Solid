# 040 — Bringing your existing books in (CSV import)

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Nobody starts from nothing. A new user arrives with a chart of accounts, a customer list and a vendor list in
some other program, and re-typing them is how a migration dies. Import those three lists from CSV, and say
plainly what will happen *before* anything is written.

## Scope
New `migration` module. Three list kinds: `accounts`, `customers`, `vendors`. It writes through the existing
services (`AccountService`, `BillingService`, `PayableService`), so every rule those enforce — code uniqueness,
parent must be a header of the same type, tax-line compatibility — applies to an imported row exactly as it does
to one typed in.

**No money is imported.** Balances come in through opening balances (spec 028), which the ledger can prove;
a column of balances in a CSV cannot be proved and would make the trial balance a matter of trust.

## How it works
1. **Preview** (`POST .../imports/{kind}/preview`) parses the file and returns one result row per data row:
   what will be created, what will be skipped because it already exists, and what is wrong and why, with the
   line number from the file.
2. **Commit** (`POST .../imports/{kind}`) re-parses, re-checks, and refuses the whole file if any row has a
   problem. Either the whole list goes in or none of it does; a half-imported chart of accounts is worse than
   no import.
3. Re-running the same file creates nothing the second time: rows that match something already there are
   skipped, not duplicated. Import is therefore safe to retry after a failure.

## The columns
Header row required. Names are matched case-insensitively, ignoring spaces and underscores, so `Tax Line` and
`tax_line` are the same column. Unknown columns are ignored but reported in the preview, because a column the
importer silently drops is how data goes missing.

- **accounts** — required `code`, `name`, `type` (asset/liability/equity/income/expense);
  optional `subtype`, `parent` (the *code* of a header account, either already in the books or earlier in the
  same file), `header` (yes/no), `tax line`.
- **customers** — required `name`; optional `email`, `phone`, `billing address`, `notes`.
- **vendors** — required `name`; optional `email`, `phone`, `address`, `tax classification`, `1099` (yes/no),
  `default expense account` (an account *code*).

Existing-row matching: accounts by `code`, customers and vendors by name (case-insensitive, trimmed).

## Rules
- At most 2,000 data rows per file; blank lines are ignored.
- A row whose required field is empty is a problem, not a silent skip.
- Duplicate keys *within the file* are a problem — the file disagrees with itself, and guessing which one the
  person meant is not the importer's job.
- `yes/no`, `y/n`, `true/false`, `1/0` are all accepted for the boolean columns; anything else is a problem.
- The commit runs in one transaction. Nothing is written when it refuses.
- Nothing is inferred from a blank cell. A missing optional value stays missing.

## Data contracts
`POST /api/v1/orgs/{orgId}/entities/{entityId}/imports/{kind}/preview` with `{"csv": "..."}` →
```
{kind, committed, totalRows, created, skipped, problemCount, ready, ignoredColumns: [],
 rows: [{line, key, action: "create"|"skip"|"error", detail}]}
```
On a preview, `committed` is false and `created`/`skipped` are what *would* happen.

`POST /api/v1/orgs/{orgId}/entities/{entityId}/imports/{kind}` with the same body → the same shape with
`committed: true`, or **HTTP 422** carrying the same report with `committed: false` and nothing written.
A file the parser cannot use at all (no header, a missing required column, too many rows) is a 409 problem
document instead, because there is nothing to report row by row.

## Acceptance criteria
1. A good accounts file imports; the accounts exist with their codes, names, types and parents.
2. A parent named earlier in the same file is linked; a parent that is not a header account is a problem.
3. Re-importing the same file a second time creates nothing and reports every row as skipped.
4. A file with one bad row writes **nothing** — the good rows are not created either — and the response names
   the line number and the reason.
5. Customers and vendors import, including the boolean and account-code columns, and a vendor's
   `default expense account` resolves by code.
6. A row count over the limit is refused before anything is parsed row by row.
7. Preview writes nothing.
8. Another organization gets 404.

## Out of scope
Opening balances and transactions (spec 028 and the journal import that will follow), field mapping in the UI,
Excel files, and undo of a completed import.
