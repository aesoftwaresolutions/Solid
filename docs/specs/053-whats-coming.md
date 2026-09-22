# 053 — What is coming

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
The cash-flow statement says where the money went. The question that keeps a small business awake is the other
one: what is due, and will there be enough? Every piece of the answer is already in the books — open invoices
with due dates, open bills with due dates, the templates that will bill and post again — and nothing puts them
on one page.

## Scope
`reporting` module, one read-only endpoint and a card on the reports screen. No new tables, no new figures:
everything listed is a record that already exists or a template occurrence that is already scheduled.

## What it is, and what it is not
It is a **list of what is scheduled**, with running arithmetic from today's cash. It is **not a forecast**:
it knows nothing about the sale you have not made, the bill that has not arrived, the customer who pays late,
or payroll you run by hand. The response says so in `note`, and the screen prints it. Anyone reading a
projected balance as a prediction is reading it wrong, and the wording is there to stop that.

## What is listed
Between `from` (default today) and `to` (default 90 days out):

| kind | where it comes from | direction |
|---|---|---|
| `invoice_due` | an issued invoice with a balance still owed, on its due date | in |
| `bill_due` | an entered bill with a balance still owed, on its due date | out |
| `recurring_invoice` | each occurrence a recurring invoice template will create, on its date | in |
| `recurring_entry` | each occurrence a recurring journal entry will post, by its cash effect | in or out |

A recurring journal entry counts only its effect on the bank and cash accounts — a depreciation template moves
no money and so is not listed.

## Data contract
`GET /api/v1/orgs/{o}/entities/{e}/reports/whats-coming?from=&to=` →
```
{from, to, currency, openingCash, note,
 items: [{date, kind, description, reference, amountIn, amountOut, projectedBalance}],
 totals: {in, out, net, projectedClosing},
 lowestPoint: {date, balance} | null}
```
`lowestPoint` is the worst day the arithmetic reaches — the number worth looking at.

## Rules
- Overdue invoices and bills are listed on `from` (today by default), not on the date they were due: the money
  is owed now, and pretending otherwise flatters the arithmetic.
- Only issued invoices and entered bills count. A draft is not owed by anyone.
- Recurring occurrences use the same date arithmetic the templates themselves use, so this page cannot
  disagree with what a run would create.
- A deactivated template is not listed.
- The running balance starts from the cash the balance sheet shows today and is plain addition and
  subtraction in whole cents.
- Everything is entity-scoped; another organization gets 404.

## Acceptance criteria
1. An issued invoice due in 30 days appears as money in on that date, with its balance due — not its total.
2. An overdue bill appears on the first day of the range, with a note that it is overdue.
3. A monthly recurring invoice appears once per month at its amount, on the dates the run would use.
4. A recurring journal entry that moves cash appears; one that does not (depreciation) does not.
5. Drafts, paid invoices and deactivated templates are absent.
6. The running balance starts at today's cash, and the lowest point is the smallest balance reached.
7. The note says plainly that this lists what is scheduled and is not a forecast.
8. Another organization gets 404.

## Out of scope
Any prediction of unscheduled money, payment-behaviour modelling ("this customer pays 12 days late"),
scenarios, and alerts.
