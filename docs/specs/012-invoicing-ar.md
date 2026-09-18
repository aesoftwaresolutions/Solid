# 012 — Customers, invoices & receipts (accounts receivable)

**Status:** Done · **Owner review:** Needed

## Goal
Bill customers and track who owes what, with every invoice and payment posting correct double-entry journal entries automatically.

## Scope
`billing` module (`ar_ap` schema): customers, invoices with lines, finalize/void, customer payments applied to invoices, and an A/R aging report.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST/GET /customers` `{name, email?, phone?, billingAddress?, notes?}`
- `POST /invoices` (draft) `{customerId, issueDate, terms: due_on_receipt|net_15|net_30|net_60, invoiceNumber?, memo?, lines:[{description, quantity:"2", unitPrice:{amount,currency}, incomeAccountId}]}`
- `PATCH /invoices/{id}` (drafts only, same body) · `GET /invoices?status=` · `GET /invoices/{id}`
- `POST /invoices/{id}/finalize` → posts the journal entry, status `open`
- `POST /invoices/{id}/void` → posts a reversal, status `void`
- `POST /payments` `{customerId, receivedDate, depositAccountId, method?, reference?, applications:[{invoiceId, amount}]}` → posts the journal entry
- `GET /reports/accounts-receivable-aging?asOf=` → per customer: current, 1–30, 31–60, 61–90, 90+, total

Invoice totals are computed server-side: `line amount = round(quantity × unitPrice, currency, HALF_UP)`, `total = Σ line amounts`.

## Posting rules
- Finalize: debit the entity's A/R account (subtype `ar`) for the total, credit each line's income account for its amount. Entry date = issue date, source `invoice`.
- Payment: debit the deposit account (bank/asset), credit A/R for the total applied. Entry date = received date, source `payment`.
- Void: reverse the invoice's entry (only if nothing has been applied to it).

## Acceptance criteria
1. Quantity is a decimal string with up to 4 decimals; `2.5 × 40.00 = 100.00`; `3 × 33.333 …` is rejected unless the unit price fits the currency (unit prices are money, so 2 decimals) — line amount rounds HALF_UP and the invoice total is the sum of rounded line amounts.
2. Invoice numbers: auto-assigned per entity as `INV-0001`, `INV-0002`… when not supplied; explicit numbers must be unique per entity (409 `INVOICE_NUMBER_TAKEN`).
3. Due date comes from terms: due_on_receipt = issue date, net_15/net_30/net_60 = +15/30/60 days.
4. Only drafts can be edited (409 `INVOICE_NOT_DRAFT`); finalize posts a balanced entry whose A/R debit equals the invoice total and whose credits match the line income accounts; `journalEntryId` is returned.
5. Line income accounts must be active, non-header income accounts of the entity (409 `ACCOUNT_NOT_POSTABLE`); an invoice needs at least one line and a positive total.
6. Payments: amounts must be positive, may not exceed each invoice's open balance (409 `OVERPAYMENT`), and all applications must belong to the payment's customer (409). Applying the full balance sets the invoice `paid`; part of it sets `partially_paid`.
7. Void is allowed only when nothing is applied (409 `INVOICE_HAS_PAYMENTS`) and reverses the original entry.
8. A/R aging at a date buckets each open invoice by days past due and totals per customer; the sum of all buckets equals the A/R account balance in the trial balance at that date.
9. Locked periods block finalize, payment and void (409 `PERIOD_LOCKED`).
10. RLS on all `ar_ap` tables; viewers can read but not change.

## Out of scope
Sales tax on invoices (slice for the sales-tax module), recurring invoices, PDF/email delivery, credit notes, deposits/prepayments, foreign currency.
