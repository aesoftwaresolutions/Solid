# 037 — Sales tax on invoices

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
A business that charges sales tax needs three things: to add it to an invoice, to keep it out of income (it is
someone else's money, held on the way to the state), and to know what it owes and for which period.

## Scope
New `salestax` module (`stx` schema) plus the smallest possible change to invoicing: an optional tax rate per
invoice line. No rate is ever supplied by Solid — **you enter your own rates**, because they vary by state, county,
city and district, and change.

## Data contracts
- `stx.tax_rate` — `id, org_id, entity_id, jurisdiction, rate_percent (numeric 7,4), liability_account_id,
  effective_from, effective_to, note, is_active`
- `ar_ap.invoice_line.tax_rate_id` and `tax_amount_minor`; `ar_ap.invoice.tax_total_minor`

Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `GET|POST /sales-tax-rates`, `POST /sales-tax-rates/{id}/deactivate`
- `POST /invoices` line gains optional `taxRateId`
- `GET /reports/sales-tax?from=&to=` →
  `{from, to, currency, jurisdictions: [{jurisdiction, ratePercent, taxableSales, taxCollected}], totalTaxable,
    totalCollected, note}`

## Rules
- **Solid never supplies a rate.** A rate is created by the person, with the jurisdiction it belongs to and a note
  for where they got it. The report repeats that they are responsible for the rate and the filing.
- Tax is calculated per line as `round(line amount × rate)`, half-up, on integer minor units — never on a float,
  and never on the invoice total (rounding per line is what a state expects and what a customer can check).
- Tax collected is a **liability**, credited to the rate's liability account when the invoice is issued. It never
  touches an income account, so the P&L is unaffected by what the customer paid in tax.
- A rate in use is deactivated, never deleted: old invoices must keep showing the rate they charged.
- A rate must belong to this entity and be active on the invoice's issue date, or the invoice is refused.
- The report counts **issued** invoices (not drafts, not voided) by issue date, on the accrual the ledger already
  records; it says so, because a state may want cash basis and that is a different number.

## Acceptance criteria
1. A rate is created with a jurisdiction, a percentage and a liability account, and is listed.
2. An invoice line with a tax rate shows the tax amount, the invoice shows a tax total, and its total is
   lines + tax.
3. Issuing that invoice debits receivables for the gross, credits income for the net and credits the liability
   account for the tax — the P&L shows only the net.
4. Tax is rounded per line, half-up: 3 lines of 33.33 at 8.25% give 2.75 + 2.75 + 2.75, not 8.24 spread oddly.
5. A rate from another entity, an inactive rate, or one not yet effective is refused with 400/409.
6. The sales-tax report groups by jurisdiction with taxable sales and tax collected, ignores drafts and voided
   invoices, and carries the note about who is responsible.
7. Deactivating a rate leaves existing invoices unchanged and keeps it out of new ones.

## Out of scope
Rate lookup by address, nexus tracking, filing or remitting, marketplace rules, sales tax on bills (use tax),
partial exemptions and exemption certificates, and cash-basis reporting.
