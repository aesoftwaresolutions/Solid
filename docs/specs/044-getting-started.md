# 044 — The first ten minutes

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Someone installs Solid on their own server, signs in, and sees an empty dashboard full of zeros. Nothing tells
them what to do first, and the order matters: a chart of accounts before opening balances, opening balances
before the first import. This slice says what is done, what is next, and what is genuinely optional.

## Scope
New `setup` module. One read-only endpoint that asks the other modules' public services what exists; it owns
no tables and stores no state, so nothing can go stale or disagree with the books.

## The steps
| key | Done when | Optional when |
|---|---|---|
| `entity_details` | the entity has a home state, so state rules can ever apply | never |
| `chart_of_accounts` | the entity has at least one account | never |
| `bank_account` | at least one bank account exists to import into | never |
| `opening_balances` | an opening-balance entry has been posted | always — a business that started with Solid has nothing to bring in |
| `tax_lines` | every income and expense account maps to a tax line | for a household entity, which files no Schedule C |
| `first_entry` | at least one journal entry has been posted | never |

Each step reports `status` (`done`, `todo` or `optional`), a sentence saying what it means, and the screen that
does it. `complete` is true when every step that is not optional is done.

## Rules
- Nothing here writes. Asking "how set up am I?" must never change the books.
- Every figure comes from the module that owns it, through its public service — the setup module reads no
  tables of its own.
- An optional step that *has* been done reports `done`, not `optional`: the person did it, and hiding that
  would be rude.
- The banner disappears once `complete` is true. A finished setup should not keep nagging.

## Data contract
`GET /api/v1/orgs/{orgId}/entities/{entityId}/setup` →
```
{complete, doneCount, requiredCount,
 steps: [{key, title, status: "done"|"todo"|"optional", detail, where}]}
```

## Acceptance criteria
1. A brand-new entity reports every required step as `todo` and `complete: false`.
2. Applying a chart-of-accounts template turns `chart_of_accounts` to `done` and leaves the rest alone.
3. Posting an opening balance turns that step `done` even though it is optional.
4. A household entity reports `tax_lines` as `optional`, a sole proprietor as `todo` until every income and
   expense account is mapped.
5. When every required step is done, `complete` is true and `doneCount` equals `requiredCount`.
6. The endpoint writes nothing: the entity's accounts, entries and bank accounts are unchanged after calling it.
7. Another organization gets 404.
8. The dashboard shows the remaining steps as links, and stops showing them once setup is complete.

## Out of scope
Doing the steps for the person, sample data, and any "skip setup" flag — the answer is computed from the books,
so there is nothing to skip.
