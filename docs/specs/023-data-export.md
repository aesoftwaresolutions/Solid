# 023 — Export everything

**Status:** Done · **Owner review:** Needed

## Goal
Self-hosting only means something if the data can leave. Give every entity a one-click export of its books as plain
CSV files that open in any spreadsheet — for an accountant, for a migration away from Solid, or just for peace of
mind.

## Scope
New `exports` module. It reads through the other modules' public services (no direct access to their tables) and
streams a ZIP. It adds no new storage and changes no data.

## Data contracts
`GET /api/v1/orgs/{orgId}/entities/{entityId}/export.zip` → a ZIP containing:
- `README.txt` — what this export is, when it was taken, and what is *not* in it
- `accounts.csv` — the chart of accounts
- `journal-entries.csv`, `journal-lines.csv` — the ledger, lines referencing their entry id
- `bank-transactions.csv` — imported bank activity and its status
- `invoices.csv`, `bills.csv` — receivables and payables with their balances
- `documents.csv` — the vault's **metadata** (filename, kind, size, SHA-256, what it is attached to)

## Rules
- Money is written as a plain decimal string in its own column, with the currency in a neighbouring column. No
  thousands separators, no currency symbols, no floats.
- Dates are ISO-8601 (`2026-09-18`). Ids are the same UUIDs the API uses, so rows can be joined.
- Every field is quoted when it contains a comma, quote or newline, and quotes inside are doubled.
- A value starting with `=`, `+`, `-` or `@` is prefixed with a single quote, so a description someone typed can
  never execute as a formula when the file is opened in a spreadsheet.
- The export contains **no file bytes** — documents live in the vault and come out through the backup
  (`docs/operations.md`); `README.txt` says so.
- The export is scoped to one entity and goes through the usual org membership check, so it can never contain
  another organization's data.

## Acceptance criteria
1. The ZIP contains exactly the files listed above, and every CSV has a header row.
2. `accounts.csv` and `journal-lines.csv` hold the entity's real rows, with amounts as plain decimal strings and
   entry ids that join `journal-lines.csv` to `journal-entries.csv`.
3. A description containing a comma, a quote and a newline survives a round trip through a CSV parser unchanged.
4. A description starting with `=` is written prefixed with `'` so a spreadsheet cannot execute it.
5. `documents.csv` lists metadata only — no file contents — and `README.txt` says where the files themselves are.
6. A member of another organization gets 404, and an anonymous caller 401.
7. An entity with no data still exports a valid ZIP with header-only CSVs.

## Out of scope
Whole-organization exports, JSON or QBO/IIF formats, importing an export back in, scheduled exports, and including
the document files themselves.
