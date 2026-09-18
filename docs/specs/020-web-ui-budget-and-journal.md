# 020 — Budget and journal screens

**Status:** Done · **Owner review:** Needed

## Goal
Two things still need a terminal: planning a month's budget, and making a manual journal entry (an opening balance,
an owner's draw, a correction). Both belong on screen.

## Scope
Frontend only. Two pages plus their API-client entries and navigation:
- **Budget** — edit one month's plan account by account, then see budget versus actual for that month.
- **Journal** — list entries for a date range, write a new entry with as many lines as needed, post a draft,
  reverse a posted entry, and see and set the period lock.

## Rules
- Debits and credits are entered as two separate columns; the page sends one line per row with a **signed** amount
  (debit positive, credit negative), because that is what the API takes.
- The page shows the running debit and credit totals and refuses to submit while they differ — the server checks
  this too, but the person should not have to submit to find out.
- Posted entries are never edited: the only actions offered are **reverse** (posted) and **post** or **delete**
  (draft), matching the ledger's rules.
- The period lock is shown wherever it can block an action, and the server's `PERIOD_LOCKED` message is displayed
  as-is when it does.
- Budget rows are shown one per postable income and expense account, so planning a month is filling in a column.
  Blank is zero, and only non-zero lines are sent.
- Money is typed as decimal strings and sent as `{amount, currency}`.

## Acceptance criteria
1. The budget page lists the entity's income and expense accounts, loads any saved amounts for the chosen month,
   and saves the month with only the non-empty lines, as `{amount, currency}` values.
2. Changing the month reloads that month's budget and its comparison; a month with no budget saved yet shows an
   empty form rather than an error.
3. The comparison table shows budget, actual and variance per account and flags over-budget rows.
4. The journal page creates a balanced two-line entry and sends signed amounts (debit positive, credit negative).
5. Submitting is blocked while debits and credits differ, with the imbalance shown.
6. A draft entry offers post and delete; a posted entry offers only reverse, and reversing calls the reverse
   endpoint.
7. The period lock is displayed, can be set, and a `PERIOD_LOCKED` error from any action is shown with its message.

## Out of scope
Editing a draft's lines after saving, attaching documents from these screens, recurring entries, budget copy-forward,
multi-currency, and any charting of the comparison.
