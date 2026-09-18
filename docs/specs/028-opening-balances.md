# 028 — Opening balances

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Nobody starts at zero. Someone moving to Solid has a bank balance, a credit-card balance, unpaid invoices and a
loan. Let them type those in once, in the form they already have them (a positive number per account), and get one
correct, balanced journal entry.

## Scope
`ledger` module. One new endpoint pair and the screen for it. No new tables: an opening-balance entry is an ordinary
posted journal entry with source `opening_balance`, so it reverses, reports and exports like everything else.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST /opening-balances` `{asOfDate, equityAccountId?, balances: [{accountId, amount}]}` → the journal entry
- `GET /opening-balances` → the entry if one exists, otherwise 404

## Rules
- Amounts are entered as people read them off a statement: **positive means the account's natural balance.** The
  server applies the sign — asset and expense accounts are debited, liability, equity and income accounts are
  credited. A negative amount means the opposite (an overdrawn account, a contra balance).
- The difference between the two sides goes to the opening-balance equity account: `equityAccountId` when given,
  otherwise the entity's account with subtype `opening_balance`. If neither exists → 400 saying which account to
  create. That plug is what makes the entry balance, and it is the honest representation of "what the owner brought
  in".
- Only postable accounts of this entity, no headers, no archived accounts, no duplicates.
- Only one opening-balance entry per entity: a second attempt is refused with 409 `OPENING_BALANCES_EXIST`, naming
  the existing entry. Correcting it means reversing that entry, exactly like any other posting mistake.
- The entry is posted (never left as a draft), dated `asOfDate`, and respects the period lock like everything else.
- Accounts left out are simply not in the entry; a zero amount is skipped rather than written as a zero line.

## Acceptance criteria
1. Balances of 5,000 in checking and 1,200 on a credit card produce one posted entry debiting checking 5,000,
   crediting the card 1,200 and crediting opening-balance equity 3,800.
2. The resulting balance sheet as of that date shows those balances, and the entry's source is `opening_balance`.
3. A negative amount on an asset account becomes a credit (an overdrawn bank account is representable).
4. A second call is refused with 409 `OPENING_BALANCES_EXIST` and names the existing entry; after reversing that
   entry, a new one is accepted.
5. A header, archived, other-entity or duplicated account is refused with 400.
6. With no `equityAccountId` and no `opening_balance` account on the chart, the error says which account to create.
7. `GET /opening-balances` returns the entry when it exists and 404 when it does not.

## Out of scope
Per-customer or per-vendor opening balances (enter those as invoices and bills), opening inventory quantities,
mid-year conversions with year-to-date income and expenses split by month, and importing balances from a file.
