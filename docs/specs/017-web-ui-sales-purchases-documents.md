# 017 — Web UI for sales, purchases and documents

**Status:** Done · **Owner review:** Needed

## Goal
Finish the Phase 1 web UI: the invoicing, bills and receipts features already in the API (slices 012, 013, 016)
need screens, so a sole proprietor can run the whole month without curl.

## Scope
Frontend only — no API changes. Three new pages plus the navigation and API-client entries they need:
- **Sales** — customers, invoices (draft → finalize → void), recording a customer payment, A/R aging.
- **Purchases** — vendors, bills (draft → approve → void), paying bills, A/P aging, 1099 candidates.
- **Documents** — upload a receipt, list and filter by kind, download, link to a record, delete.

## Rules
- Money is entered and displayed as decimal strings and sent as `{amount, currency}`; no floats anywhere.
- Every screen shows the server's problem-detail message and `code` on failure (existing `ErrorMessage`).
- Buttons that change data are disabled while the request is in flight so nothing is submitted twice.
- Amounts render with the existing `formatMoney` (negatives in parentheses).
- The 1099 panel shows the server's `note` and never renders a threshold the server marked unknown.
- Document downloads go through a normal link to the content URL so the browser saves the original filename.

## Acceptance criteria
1. Sales page lists customers and invoices, creates a customer, and creates a draft invoice from a line
   (description, quantity, unit price, income account) — the request body carries `{amount, currency}` money.
2. Finalizing a draft invoice calls `/invoices/{id}/finalize` and the row's status updates without a page reload.
3. Recording a payment posts one application per invoice with the entered amount and refreshes the list.
4. Purchases page creates a vendor and a draft bill, approves it, and pays it from a chosen account.
5. The 1099 panel renders each candidate with what is missing, and shows the note when the threshold is unknown.
6. Documents page uploads a file (multipart), lists it, filters by kind, links it to a record, and refuses to
   delete while linked — showing the `DOCUMENT_LINKED` message from the server.
7. An API failure on any of these screens shows the server's message and does not leave the button disabled.

## Out of scope
Invoice PDFs and emailing, editing a finalized invoice, partial-payment shortcuts, drag-and-drop uploads,
document previews/thumbnails, the personal-finance screens (later phase).
