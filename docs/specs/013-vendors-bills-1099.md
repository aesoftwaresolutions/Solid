# 013 — Vendors, bills (A/P) and 1099 tracking

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed (1099 thresholds & box mapping)

## Goal
Track what the business owes, pay it, and know at year end which contractors need a 1099-NEC — using the reporting threshold that applies to the tax year.

## Scope
`billing` module (`ar_ap` schema): vendors with W-9 details, bills with lines, approve/void, bill payments, A/P aging, and a 1099 candidate report.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST/GET /vendors` `{name, email?, phone?, address?, taxIdLast4?, taxClassification?, is1099Vendor?, defaultExpenseAccountId?}`
- `POST /bills` (draft) `{vendorId, billDate, terms, vendorReference?, memo?, lines:[{description, amount, expenseAccountId}]}`
- `PATCH /bills/{id}` (drafts only) · `GET /bills?status=` · `GET /bills/{id}`
- `POST /bills/{id}/approve` → posts the entry, status `open`
- `POST /bills/{id}/void` → reverses (only when unpaid)
- `POST /bill-payments` `{vendorId, paidDate, paymentAccountId, method?, reference?, applications:[{billId, amount}]}`
- `GET /reports/accounts-payable-aging?asOf=`
- `GET /reports/form-1099-candidates?taxYear=2026` → per vendor: total paid in the year, whether it meets the threshold, and what is missing (W-9 details)

## Posting rules
- Approve a bill: debit each line's expense account, credit the entity's A/P account (subtype `ap`). Entry date = bill date, source `bill`.
- Pay a bill: debit A/P, credit the payment account (bank or credit card). Entry date = paid date, source `bill_payment`.
- 1099 amounts count **payments made in the calendar year** (cash basis, as the IRS requires), not bills approved.

## Thresholds (must be reviewed against the current IRS instructions)
`backend/src/main/resources/tax-rules/form-1099-nec-thresholds.json` holds one entry per tax year with a `source` note:
- tax years through 2025: **$600**
- tax years from 2026: **$2,000** (One Big Beautiful Bill Act; inflation-indexed from 2027 — the indexed amounts are `TODO` until the IRS publishes them)
A tax year with no entry (e.g. 2027) makes the report return `thresholdKnown: false` and refuse to judge, rather than guessing.

## Acceptance criteria
1. Bills mirror invoices: draft → approve → paid/partially_paid, void only when unpaid, locked periods refused (409 `PERIOD_LOCKED`).
2. Bill lines must use active non-header **expense or asset** accounts (asset allows buying equipment on credit); at least one line, positive total.
3. Paying a bill accepts a bank asset or credit-card liability payment account and never exceeds the open balance (409 `OVERPAYMENT`).
4. A/P aging buckets by days past due and its total equals the A/P balance in the trial balance.
5. 1099 report: for tax year 2026, a vendor marked `is1099Vendor` paid $2,100 is `meetsThreshold: true`; one paid $1,500 is false; for tax year 2025 the same $1,500 vendor is true ($600 threshold). Amounts come from payments in that calendar year.
6. The report lists `missingInformation` for vendors that meet the threshold but have no `taxIdLast4` or `taxClassification` (so the owner knows to collect a W-9), and never includes non-1099 vendors (e.g. corporations marked `is1099Vendor: false`).
7. An unknown tax year returns `thresholdKnown: false` with a message and no pass/fail judgement.
8. RLS on new tables; viewers read-only.

## Out of scope
Actual 1099 e-filing through IRIS (Phase 4), 1099-MISC boxes other than nonemployee compensation, W-9 collection workflow, vendor credits.
