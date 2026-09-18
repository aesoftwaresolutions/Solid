# 032 — Customer statements

**Status:** Done · **Owner review:** Needed

## Goal
"What do I owe you?" should be one link, not a hunt through invoices. Give each customer a statement for a period:
what they owed at the start, what was invoiced, what they paid, and what is left.

## Scope
`billing` module. Built from the invoices and payments already stored — no new tables. JSON for the screen, PDF for
sending, using the same renderer style as the invoice PDF (spec 031).

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}/customers/{customerId}`
- `GET /statement?from=&to=` →
  `{customer, from, to, currency, openingBalance, lines: [{date, type, reference, description, charge, payment, balance}], closingBalance}`
- `GET /statement.pdf?from=&to=` → the same thing as a PDF

## Rules
- Only **issued** invoices count: drafts have not been sent, and voided invoices never were owed. A payment counts
  on the day it was received, for the amount applied to this customer's invoices.
- The opening balance is everything charged minus everything paid strictly **before** `from`; every line then
  carries the running balance, so the last line equals `closingBalance`.
- Lines are ordered by date, and within a date invoices come before payments — a payment cannot reduce a balance
  the statement has not shown yet.
- Charges and payments are separate columns, both positive; the sign lives in the column, not in the number.
- `to` defaults to today and `from` to the first of the month twelve months earlier when they are omitted; `from`
  after `to` is a 400.
- The PDF says the period and the closing balance plainly, and carries the same "Prepared with Solid" footer.

## Acceptance criteria
1. A statement over a period lists that period's issued invoices and payments, in date order, invoices first.
2. The running balance on the last line equals `closingBalance`, and `openingBalance` covers everything before
   `from`.
3. Draft and voided invoices never appear.
4. A payment applied to two invoices appears once, for the total applied in that period.
5. The PDF starts with `%PDF` and its text contains the customer name, the period and the closing balance.
6. `from` after `to` is refused with 400; another organization gets 404 and an anonymous caller 401.

## Out of scope
Emailing statements, dunning letters and reminders, finance charges on overdue balances, vendor statements, and
statements across several entities.
