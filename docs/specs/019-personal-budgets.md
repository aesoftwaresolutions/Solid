# 019 — Personal accounts and monthly budgets

**Status:** Done · **Owner review:** Needed

## Goal
Solid is meant to cover the household as well as the business. Give a personal entity a chart of accounts that fits
a household, a monthly budget, and the one report people actually use: budget versus what really happened.

## Scope
New `budget` module (tables in the `pf` schema) plus a `personal` chart-of-accounts template for entities of kind
`individual`. Actuals come from the existing ledger through the reporting module — no second source of truth.

## Data contracts
- `pf.budget` — one per entity per month: `id, org_id, entity_id, period_month (first of month), currency`.
- `pf.budget_line` — `budget_id, account_id, amount_minor` (zero or more), one line per account.

Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `PUT /budgets/{yyyy-mm}` `{lines: [{accountId, amount}]}` → the saved budget (replaces that month's lines)
- `GET /budgets/{yyyy-mm}` → `{periodMonth, currency, lines: [{accountId, code, name, type, amount}], totals}`
- `GET /reports/budget-vs-actual?month=yyyy-mm` →
  `{periodMonth, currency, income: [row], expenses: [row], totals: {…}}` where a row is
  `{accountId, code, name, budget, actual, variance, overBudget}`

## Rules
- Budgets cover **income and expense** accounts only, never headers, never archived accounts, and only accounts
  belonging to that entity. Anything else → 400.
- Budget amounts are zero or positive and in the entity's base currency; money follows the usual `Money` rules.
- `variance` is written so positive always means "good news": for income it is `actual - budget`, for expenses it is
  `budget - actual`. `overBudget` is true only for an expense account whose actual exceeds its budget.
- Actual figures come from `ReportService.profitAndLoss` for that month, so the budget report and the P&L can never
  disagree.
- Accounts with a budget but no activity appear with a zero actual; accounts with activity but no budget appear with
  a zero budget, so nothing is hidden.
- Saving a budget twice for the same month replaces its lines rather than adding to them.
- The `personal` template applies to entities of kind `individual` and carries **no tax-line mappings** — mapping a
  household's accounts to 1040 lines needs a reviewed rule pack (a later slice), and guessing is not allowed.

## Acceptance criteria
1. Applying the `personal` template to an `individual` entity creates the household accounts; applying it to a
   `sole_prop` entity is refused.
2. `PUT /budgets/2026-10` saves the lines and `GET /budgets/2026-10` returns them with the account code and name.
3. A second `PUT` for the same month replaces the lines (no duplicates, no leftovers).
4. Budgeting a header, archived, balance-sheet or other entity's account is refused with 400.
5. With real journal entries in the month, `budget-vs-actual` reports actual amounts matching the P&L, variance in
   the "positive is good" direction, and `overBudget` only for overspent expense accounts.
6. An account budgeted but unused shows actual 0; an account used but not budgeted shows budget 0 and appears in the
   report.
7. Totals add up: budgeted income minus budgeted expenses equals the budgeted net, and the same for actuals.

## Out of scope
Rolling budgets and annual budgets, envelope/zero-based budgeting, copying last month forward, budget alerts and
notifications, net-worth tracking, personal tax-line mapping, and the budget screens in the web UI (next slice).
