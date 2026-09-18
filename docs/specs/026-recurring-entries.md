# 026 — Recurring journal entries

**Status:** Done · **Owner review:** Needed

## Goal
Rent, a loan payment, a subscription, depreciation of a small asset — the same entry, every month, typed by hand.
Define it once, then post the months that are due.

## Scope
`ledger` module (`gl` schema): a recurring template with its own lines, a schedule, and a run that posts every
occurrence due up to a date. Posting goes through the existing journal service, so balance, immutability, period
locks and the hash chain all apply unchanged.

## Data contracts
- `gl.recurring_entry` — `id, org_id, entity_id, name, memo, frequency (monthly|quarterly|annual), start_date,
  end_date, day_of_month, is_active`
- `gl.recurring_line` — `recurring_id, line_no, account_id, amount_minor, currency, memo`
- `gl.recurring_occurrence` — `recurring_id, occurrence_date, journal_entry_id`, primary key
  `(recurring_id, occurrence_date)`: the record of what has already been posted.

Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST /recurring-entries` `{name, memo?, frequency, startDate, endDate?, dayOfMonth?, lines:[{accountId, amount, memo?}]}`
- `GET /recurring-entries` · `GET /recurring-entries/{id}` — each with its lines and `nextDate`
- `POST /recurring-entries/{id}/deactivate` — stops it without deleting its history
- `POST /recurring-entries/run` `{through}` → `{posted: [...], skipped: [{date, reason}]}`

## Rules
- Lines must balance to zero and be in the entity's base currency, exactly like a manual entry.
- `dayOfMonth` defaults to the start date's day and is clamped to the length of each month, so the 31st becomes the
  30th in June and the 28th or 29th in February — never spills into the next month.
- An occurrence is posted **once**: `(recurring_id, occurrence_date)` is unique, so running the same range twice
  changes nothing. This is the property that makes "run it again, I'm not sure it worked" safe.
- Posted entries carry source `recurring` and the template's id, so they can be traced back.
- An occurrence that the ledger refuses (a locked period, an archived account) is reported as **skipped with the
  server's own message**; the rest of the run still posts. One bad month must not block the others.
- Deactivating stops future occurrences; already posted entries are untouched (they are immutable).
- Nothing runs on a timer — a person (or a cron calling the API) asks for it. The instance never posts on its own.

## Acceptance criteria
1. A monthly template starting 2026-01-15 and run through 2026-03-31 posts exactly three entries, dated the 15th.
2. Running the same range again posts nothing new and reports no duplicates.
3. Running through a later date posts only the months in between.
4. A template with `dayOfMonth` 31 posts on the last day of a short month (30 June, 28 or 29 February).
5. Unbalanced lines, a wrong currency, or an account from another entity are refused at creation with 400/409.
6. With a locked period, the locked months are reported as skipped with the lock's message while later months post.
7. Deactivating stops new occurrences; quarterly and annual frequencies step by 3 and 12 months.

## Out of scope
Weekly or day-of-week schedules, end-of-month "last business day", automatic posting on a timer, editing a template
after creation (deactivate and make a new one), variable amounts, and recurring invoices or bills.
