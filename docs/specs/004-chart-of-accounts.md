# 004 — Chart of accounts with tax-line mapping

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed (tax line catalog)

## Goal
Each entity has a chart of accounts (COA). Every income/expense account can point at the tax form line it rolls up to, so the books can later produce a Schedule C without re-keying. A sole proprietor can start from a ready-made Schedule C template.

## Scope
- `tax` module: read-only **tax line catalog** loaded from `tax-lines/US-schedule-c.json` (codes like `F1040.SCH_C.L8`).
- `ledger` module: `gl.account` table, account API, `schedule_c` template.

## Data contracts
`gl.account(id, org_id, entity_id, code, name, type, subtype, parent_id, is_header, default_tax_line_code, is_archived, created_at)`

API (all under `/api/v1/orgs/{orgId}/entities/{entityId}`):
- `GET /accounts` → list ordered by code
- `POST /accounts` `{code, name, type, subtype?, parentId?, isHeader?, taxLineCode?}` → 201
- `PATCH /accounts/{accountId}` `{name?, taxLineCode?, archived?}` → 200
- `POST /accounts/apply-template` `{template: "schedule_c"}` → 201 list
- `GET /api/v1/tax-lines?form=F1040.SCH_C` → catalog entries `{code, form, line, label, kind: income|expense|cogs}`

## Acceptance criteria
1. Account `type` ∈ asset, liability, equity, income, expense; `code` is 1–20 chars of digits/letters/`-`/`.`, unique per entity (409 `ACCOUNT_CODE_TAKEN`).
2. Parent must belong to the same entity, have the same type, be a header account, and not be archived (409 `INVALID_PARENT`).
3. Tax line code must exist in the catalog (400) and be compatible: income lines only on income accounts; expense/cogs lines only on expense accounts; balance-sheet accounts can't have tax lines (409 `TAX_LINE_INCOMPATIBLE`).
4. `apply-template schedule_c` creates the template (headers + ~40 accounts) for an entity with no accounts; if accounts exist → 409 `COA_NOT_EMPTY`. Only allowed for entity kinds `sole_prop` and `smllc` (409 `TEMPLATE_NOT_APPLICABLE`).
5. Every expense account in the template maps to a Schedule C line; Owner's Draws has no tax line; all template tax line codes exist in the catalog (automated test).
6. Archiving an account with non-archived children → 409 `HAS_ACTIVE_CHILDREN`. Accounts are never deleted (history).
7. Row-level security on `gl.account` (covered by the RLS coverage test from 003); entity from another org → 404.

## Decisions made without owner input
- Schedule C line labels/numbers taken from the IRS Schedule C (Form 1040) layout in use for recent tax years; stored with a `source` note and **must be verified against the TY2026 form** before use in filing.
- Template account numbers use the common 1000/2000/3000/4000/5000/6000 convention.

## Out of scope
Posting (005), balances/reports (006), other templates (S-corp, household) — later.
