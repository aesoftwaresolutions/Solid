# 052 — Invoices that repeat

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
A retainer, a monthly service, a quarterly licence: the same invoice to the same customer, over and over.
Recurring journal entries have existed since slice 026; the invoices people actually send have not, and
re-typing them every month is where a small business quietly loses revenue.

## Scope
`billing` module. A template (customer, lines, frequency, start, optional end, day of month), a run that
creates the invoices due, and the screen for both. New tables `ar_ap.recurring_invoice` and
`ar_ap.recurring_invoice_line`, plus `ar_ap.recurring_invoice_occurrence` so an occurrence can only happen
once.

## How it works
Exactly as recurring entries do, and for the same reason: **nothing happens on a timer.** A person — or a
cron job calling the API — runs the templates through a date, and every occurrence due on or before it is
created once. A clock that posts on its own is a clock that posts while nobody is looking.

- Each occurrence creates a **draft** invoice, not an issued one. An invoice is a statement to a customer;
  someone should look at it before it goes. The draft carries the occurrence date as its issue date and the
  template's terms.
- An occurrence row records template plus date, so a second run — or two runs at the same moment — cannot
  create the same month twice.
- At most 120 occurrences per run, counted from the last one created, so a template that starts in 2019
  cannot flood the ledger and the cap stays per-run however old the template gets.
- A template with no active customer, or whose lines point at an archived account, is skipped with a reason
  rather than stopping the run.

## Data contract
- `POST /api/v1/orgs/{o}/entities/{e}/recurring-invoices` `{customerId, name, frequency, startDate, endDate,
  dayOfMonth, terms, memo, lines: [{description, quantity, unitPrice, incomeAccountId, taxRateId}]}`
- `GET .../recurring-invoices`, `GET .../recurring-invoices/{id}`,
  `POST .../recurring-invoices/{id}/deactivate`
- `POST .../recurring-invoices/run?through=YYYY-MM-DD` →
  `{through, created: [{recurringId, date, invoiceId, invoiceNumber}], skipped: [{recurringId, date, reason}]}`

## Rules
- Lines are priced the way an invoice's are: quantity × unit price, in the entity's currency, with the same
  sales-tax rate reference an ordinary line can carry.
- Deactivating stops future occurrences and touches nothing already created.
- The invoices it creates are ordinary drafts: editable, issuable, voidable, and counted nowhere until issued.
- Running is idempotent for a given `through` date.
- Everything is scoped to the entity, and another organization gets 404.

## Acceptance criteria
1. A monthly template run through three months ahead creates three draft invoices, one per month, with the
   template's lines and totals.
2. Running again through the same date creates nothing.
3. Running through a later date creates only the months in between.
4. The created invoices are drafts: they do not appear in accounts receivable until they are issued, and
   issuing one works normally.
5. A template deactivated after two months creates nothing further.
6. A template whose customer has been archived is skipped with a reason — archiving a customer means the
   dealings have stopped, so the template must not quietly carry on billing them — and the other templates
   still run.
7. The day of month is clamped to the length of each month (31 becomes 30 in April).
8. Another organization gets 404.

## Out of scope
Sending the invoice (there is no email), automatic issuing, price rises over time, usage-based lines, and
proration of a partial first period.
