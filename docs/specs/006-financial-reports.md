# 006 — Trial balance, profit & loss, balance sheet

**Status:** Done · **Owner review:** Needed

## Goal
Turn posted journal entries into the three core financial reports, with numbers proven against a hand-calculated sample ledger.

## Scope
`reporting` module (read-only; uses ledger's public `AccountService` and queries posted lines).

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}/reports`
- `GET /trial-balance?asOf=2026-12-31` → `{asOf, currency, rows:[{accountId, code, name, type, debit, credit}], totalDebit, totalCredit}`
- `GET /profit-and-loss?from=2026-01-01&to=2026-12-31` → `{from, to, currency, income, costOfGoodsSold, grossProfit, expenses, netIncome}`; each section `{rows:[{accountId, code, name, amount}], total}`
- `GET /balance-sheet?asOf=2026-12-31` → `{asOf, currency, fiscalYearStart, assets, liabilities, equity, totalLiabilitiesAndEquity, balanced}`
All amounts use the Money JSON format.

## Rules
- Only **posted** entries count; drafts never appear.
- Signs: assets & expenses shown as debit-positive; liabilities, equity & income shown as credit-positive. Contra accounts (e.g. Returns and Allowances, Accumulated Depreciation, Owner's Draws) therefore show as negative in their section.
- Accounts with a zero balance for the period are omitted.
- Cost of goods sold = expense accounts with subtype `cogs`.
- Balance sheet equity adds two computed rows: **Retained Earnings (prior years)** = net income from all posted entries before the fiscal year start, and **Current Year Earnings** = net income from fiscal year start through `asOf`. Fiscal year start is derived from the entity's `fiscalYearEnd` month.

## Acceptance criteria
1. Trial balance total debits = total credits for any date.
2. For the golden sample ledger (`backend/src/test/resources/fixtures/ledger-sample-2026.md`), all three reports match the hand-computed figures to the cent.
3. Reversed entries net to zero and disappear from reports; drafts are excluded.
4. Balance sheet `balanced` is true (Assets = Liabilities + Equity) for the golden ledger and for randomly generated balanced ledgers (property test).
5. Date filters: P&L includes `from` and `to` dates; entries outside are excluded; `from` after `to` → 400.
6. Fiscal year: with `fiscalYearEnd = 6`, balance sheet as of 2026-09-30 uses fiscal year start 2026-07-01.

## Out of scope
Cash vs accrual basis switch (needs A/R-A/P tracking), comparative periods, PDF/CSV export, UI.
