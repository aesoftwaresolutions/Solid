# 036 — Year-end checklist

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
At filing time the question is always the same: *is this year finished?* Answer it in one place, from the data
rather than from memory — what is still uncategorized, what is still a draft, which accounts have no tax line,
which bank accounts were never reconciled, whether depreciation was run, which 1099 vendors are missing details,
and whether the books have been closed.

## Scope
New `closing` module. It asks the existing services (bank, ledger, reporting, assets, payables, tax rule packs) and
returns one list. It changes nothing: every item is a statement about the data, plus where to go and fix it.

## Data contracts
`GET /api/v1/orgs/{orgId}/entities/{entityId}/reports/year-end-checklist?taxYear=2026` →
```
{taxYear, from, to, ready, items: [{key, title, status, detail, count, where}]}
```
`status` is `done`, `todo` or `unknown`; `where` is the screen that fixes it (`bank`, `journal`, `accounts`,
`reconcile`, `assets`, `purchases`, `instance`); `ready` is true when no item is `todo`.

## The items
1. **Bank activity categorized** — nothing left in the review queue.
2. **No draft entries** in the tax year.
3. **Accounts mapped to tax lines** — none unmapped.
4. **Bank accounts reconciled** — each has a completed reconciliation dated on or after the year end.
5. **Depreciation posted** — no month of the tax year is still unposted for an in-service asset.
6. **1099 vendors complete** — no candidate is missing a tax id, address or classification.
7. **Books closed** — the period lock is on or after the year end. This one is `todo` until it is done, because it
   is the last step, and it is listed last for that reason.
8. **Tax figures on file** — how many rule packs cover this year (`unknown` when any are missing, never `todo`:
   it is not the person's to fix, and a missing figure must not read as their mistake).

## Rules
- Every count comes from the same service the screen uses, so the checklist and the screen can never disagree.
- An item Solid cannot decide is `unknown` with a reason, never a guess and never a silent pass.
- The checklist never changes data — no auto-posting, no auto-locking. It says what is left; the person does it.
- `ready` ignores `unknown` items, and the response says so in the item's detail, so nobody reads "ready" as
  "your return is correct". It means the bookkeeping steps are done.

## Acceptance criteria
1. A fresh entity with nothing in it is not `ready`, and says so item by item.
2. An uncategorized bank transaction makes item 1 `todo` with the count; categorizing it makes it `done`.
3. A draft entry in the year makes item 2 `todo`; posting it makes it `done`.
4. An unreconciled bank account makes item 4 `todo`; a completed reconciliation dated at or after the year end
   makes it `done`.
5. An asset with unposted months in the year makes item 5 `todo`; running depreciation through December fixes it.
6. A 1099 vendor missing details makes item 6 `todo` naming what is missing.
7. Locking the period through 31 December makes item 7 `done` and, with everything else done, `ready` is true.
8. A year with no rule-pack coverage shows item 8 as `unknown`, and `ready` can still be true.

## Out of scope
Doing any of the steps for the person, a printable year-end pack, multi-entity roll-up, and anything that produces
a tax figure.
