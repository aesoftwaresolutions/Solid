# 010 — Tax-line report ("hand this to your preparer")

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed (line mapping)

## Goal
At tax time, produce one page that maps the year's books onto the tax form lines a preparer needs — plus a readiness check that says what is still missing. This is the deliverable that makes Solid useful in its first filing season, before any return preparation exists.

## Scope
`reporting` module: tax-line rollup and CSV export; uses the tax module's line catalog and the bank module's uncategorized count.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}/reports`
- `GET /tax-lines?taxYear=2026` →
  ```json
  {"taxYear":2026,"from":"2026-01-01","to":"2026-12-31","currency":"USD",
   "lines":[{"code":"F1040.SCH_C.L8","form":"F1040.SCH_C","line":"L8","label":"Advertising","kind":"expense",
             "amount":{"amount":"300.00","currency":"USD"},
             "accounts":[{"accountId":"…","code":"6010","name":"Advertising and Marketing","amount":{…}}]}],
   "unmapped":[{"accountId":"…","code":"6999","name":"Misc","type":"expense","amount":{…}}],
   "totals":{"income":{…},"costOfGoodsSold":{…},"expenses":{…},"netProfit":{…}},
   "readiness":{"draftEntries":1,"uncategorizedBankTransactions":4,"unmappedAccounts":1,"ready":false}}
  ```
- `GET /tax-lines.csv?taxYear=2026` → `text/csv` with columns `Section,Form,Line,Label,Account Code,Account Name,Amount`, a blank line, then totals and readiness rows. Downloadable (`Content-Disposition: attachment`).

## Rules
- Tax year period: for a calendar-year entity, Jan 1 – Dec 31 of that year. For a fiscal-year entity (`fiscalYearEnd ≠ 12`), the fiscal year **ending** in that calendar year.
- Only posted entries count. Line amounts follow the P&L convention: income credit-positive, expenses debit-positive (so contra accounts are negative).
- Accounts with no tax line but with income/expense activity appear in `unmapped` — they are the preparer's questions.
- Totals: income = sum of income lines and unmapped income, COGS = `cogs` lines, expenses = expense lines plus unmapped expenses, net profit = income − COGS − expenses. Net profit equals the P&L net income for the same period (asserted in tests).

## Acceptance criteria
1. Golden sample ledger (fixture from 006, extended with a mapped Schedule C account set) produces the hand-checked line amounts; Advertising `F1040.SCH_C.L8` = 300.00, Utilities line includes both Utilities and Telephone accounts, Owner's Draws never appears.
2. Net profit equals `GET /profit-and-loss` net income for the same dates.
3. An account with activity and no tax line appears exactly once in `unmapped` and is included in totals.
4. Readiness counts draft entries, uncategorized bank transactions and unmapped accounts; `ready` is true only when all three are zero.
5. Fiscal-year entity (`fiscalYearEnd = 6`) with `taxYear=2026` uses 2025-07-01 → 2026-06-30.
6. CSV export escapes quotes/commas, has one row per account under each line, and its amounts match the JSON.
7. Only members can read it (from 007); viewers may (it's a GET).

## Out of scope
Actual form generation (PDF 1040/Schedule C), 1099 issuing, depreciation schedules, state lines.
