# 069 — Business lines (what each part of the business earns)

**Status:** In review · **Owner review:** Needed · **CPA review:** Not needed (management reporting only; no tax
figure or tax-line mapping changes)

## Goal
A small company rarely does one thing. AE Software Solutions builds websites, automation, custom software and
products, and sells audits. The P&L says the business made money; it cannot say *which part* did. This slice
adds **business lines** — a named facet of the business — that invoices, bills, bank transactions and journal
lines can carry, and a profit and loss with one column per line.

The company keeps its own books in Solid, and its facets are mapped in [business/README.md](../../business/README.md).

## Decisions
- **A dimension, not an account.** Business lines never change an account, a tax line or a total. The tax-line
  report and everything a preparer receives is identical with or without them. (Splitting accounts per facet
  would have multiplied the chart of accounts and still needed the same report.)
- **On the journal line.** `gl.journal_line.business_line_id`, nullable. Null means *shared or unassigned* and
  is shown as **Shared / overhead**. Only P&L lines need one; balance-sheet lines (A/R, the bank side, sales tax)
  stay null.
- **Covered by the hash chain, compatibly.** A line's business line joins the canonical text only when present
  (`:bl=<uuid>`), so every entry posted before this slice hashes exactly as it did and `journal/verify` still
  passes. Posted lines are immutable as always: changing a posted line's business line means reverse and repost.
- **Never deleted, only archived.** Postings point at it; the database refuses `DELETE` from the application
  role. An archived line is refused on new work but is still accepted where Solid is *taking something back*: a
  reversal (void) and a credit note against an invoice line copy the original's business line even if archived.
- **Entity-scoped.** A line belongs to one entity. A trigger refuses a journal line whose business line belongs
  to another entity, on top of the organization-level foreign key and row-level security.
- **At most 50 per entity**, names unique per entity ignoring case and spacing, 1–60 characters.

## Where the business line comes from
| Source | Field | Which journal lines carry it |
|---|---|---|
| Invoice | `businessLineId` on the invoice | every income line on finalize |
| Credit note | the credited invoice line's invoice | each line that names an `invoiceLineId`; others are unassigned |
| Bill | `businessLineId` on the bill | every expense line on approve |
| Bank categorize (single and bulk) | `businessLineId` per item | the category line |
| Manual journal entry | `businessLineId` per line | that line |
| Void / reversal | the original entry | mirrored line by line |

Recurring entries, recurring invoices, quotes and opening balances do not set one yet (they create unassigned
lines, exactly as before). Follow-up candidates.

## Data contract
```
GET    /api/v1/orgs/{o}/entities/{e}/business-lines
POST   /api/v1/orgs/{o}/entities/{e}/business-lines            {"name":"Automation & AI"}
PATCH  /api/v1/orgs/{o}/entities/{e}/business-lines/{id}       {"name":"...","archived":true}   (either field)
GET    /api/v1/orgs/{o}/entities/{e}/reports/profit-and-loss-by-business-line?from=YYYY-MM-DD&to=YYYY-MM-DD
GET    /api/v1/orgs/{o}/entities/{e}/reports/profit-and-loss-by-business-line.csv?from=...&to=...
```
Report shape:
```json
{"from":"2026-01-01","to":"2026-12-31","currency":"USD",
 "columns":[{"businessLineId":"…","name":"Automation & AI","archived":false,
             "income":{…},"costOfGoodsSold":{…},"grossProfit":{…},"expenses":{…},"netIncome":{…}},
            {"businessLineId":null,"name":"Shared / overhead", …}],
 "rows":[{"accountId":"…","code":"6220","name":"Software and Subscriptions","section":"expenses",
          "amounts":[{"amount":"120.00",…},{"amount":"30.00",…},{"amount":"54.99",…}],"total":{"amount":"204.99",…}}],
 "total":{"businessLineId":null,"name":"Total", …}}
```
Columns: every active line, plus any archived line with a non-zero amount in the range, by name; then Shared /
overhead, always last. `rows[i].amounts` lines up with `columns`. Amounts are signed the way the P&L shows them.

The data export gains `business-lines.csv` and a `business_line_id` column at the end of `journal-lines.csv`.

## Acceptance criteria (all in `BusinessLineTests`; figures hand-computed)
Fixture: lines *Automation & AI* and *Websites & hosting*. Invoices: 2,400.00 (4010) to Automation; 1,500.00
(4010) + 300.00 (4900) to Web; 250.00 (4010) unassigned. Bills: 400.00 (5010) to Web; 120.00 (6220) to
Automation. Bank: −30.00 to 6220 with Web; −54.99 to 6220 unassigned. Manual entry: 500.00 to 6040 with
Automation.
1. Create, list, rename, archive. A duplicate name ignoring case/spacing → 409 `BUSINESS_LINE_NAME_TAKEN`;
   a blank name → 400.
2. Invoice and bank categorize put the line on the P&L-side journal line only (A/R and bank side null).
3. Report: Automation income 2,400.00, expenses 620.00, net 1,780.00. Web income 1,800.00, COGS 400.00, gross
   1,400.00, expenses 30.00, net 1,370.00. Shared income 250.00, expenses 54.99, net 195.01. Total income
   4,450.00, gross 4,050.00, expenses 704.99, net 3,345.01. Row 6220 = 120.00 / 30.00 / 54.99 / 204.99.
4. The total column equals the plain P&L, field by field. The tax-line report carries no business-line field.
5. An archived line refuses a new invoice (409 `BUSINESS_LINE_ARCHIVED`), still shows while it has activity, and
   voiding its invoice succeeds (reversal onto the archived line); netting to zero, it drops out of the columns.
6. A credit note against Web's 300.00 line takes it back from Web (Web income 1,500.00; Shared unchanged).
7. A business line of another entity on a journal line → 409 `BUSINESS_LINE_NOT_FOUND`.
8. After the fixture, `journal/verify` is valid.
9. A viewer reads lines and the report; a viewer's POST is 403; no session is 401.
10. The CSV header is `Section,Code,Account,Automation & AI,Websites & hosting,Shared / overhead,Total` and its
    last row is `total,,Net income,1780.00,1370.00,195.01,3345.01`.

## Screens
- **Settings → Business lines:** add, rename inline, archive/restore.
- **Sales → new invoice, Purchases → new bill, Bank → review queue:** a business-line picker, shown only once
  the entity has an active line, so nothing changes for anyone who does not use them.
- **Reports → Profit & loss by business line:** the table and a CSV download.

## Out of scope
Splitting one invoice or bill line across several business lines; per-line business lines on invoice and bill
lines (the whole document takes one); budgets by business line; business lines on recurring templates, quotes and
opening balances; allocating shared costs across lines by a rule.
