# 041 — The whole organization on one page

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Someone with a business and a household — or three LLCs — has to open each entity in turn to answer "how are we
doing, and what is waiting for me?". One request should answer it for every entity at once.

## Scope
`reporting` module, one read-only endpoint at organization level. No new tables.

## What it shows
One row per entity in the organization, in name order:

- **cash** — the bank and cash accounts as of the end of the period, the same figure the balance sheet shows
- **net income** — the profit & loss for the period
- **draft entries** and **uncategorized bank transactions** — the work waiting on that entity
- whether the entity has any accounts at all (a brand-new entity is shown as "not set up yet", not as zeros
  pretending to be a result)

## Adding up across entities
Totals are given **only when every entity keeps its books in the same currency**. Solid holds no exchange
rates, so adding dollars to pounds would mean inventing one. With more than one currency, `totals` is null,
`mixedCurrencies` is true, and each row still carries its own currency.

The total is a plain sum of the entities' figures. It is **not** a consolidation: intercompany balances are not
eliminated, and a group that files consolidated accounts needs its accountant, not this page. The response
says so in `note`, and the page prints it.

## Data contract
`GET /api/v1/orgs/{orgId}/overview?from=&to=` (default: 1 January of the current year to today) →
```
{orgId, from, to, mixedCurrencies,
 entities: [{entityId, legalName, kind, currency, setUp, cash, netIncome, draftEntries,
             uncategorizedBankTransactions, needsAttention}],
 totals: {currency, cash, netIncome} | null,
 note}
```
`needsAttention` is true when either count is above zero.

## Rules
- Every figure comes from the existing report services, so this page cannot disagree with the reports it
  summarises.
- Entities of another organization never appear; the organization must be one the caller can see, or 404.
- An entity with no chart of accounts reports `setUp: false` and zero figures.

## Acceptance criteria
1. One row per entity, in name order, with the entity's own currency.
2. Cash matches that entity's balance sheet bank/cash total, and net income matches its profit & loss for the
   same period, to the cent.
3. With two entities in the same currency, `totals` is the sum of the rows.
4. With two currencies, `totals` is null and `mixedCurrencies` is true.
5. Draft entries and uncategorized bank transactions are counted per entity.
6. A brand-new entity reports `setUp: false` rather than an error.
7. Another organization gets 404.

## Out of scope
Consolidation and intercompany elimination, currency translation, and any figure not already produced by an
existing report.
