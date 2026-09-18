# 031 — Invoice PDF

**Status:** Done · **Owner review:** Needed

## Goal
An invoice that cannot be handed to a customer is not finished. Produce a plain, printable PDF from the invoice
already in the books.

## Scope
`billing` module plus Apache PDFBox (Apache-2.0). One endpoint, no new tables, no email — the file is downloaded
and sent by whatever the person already uses.

## Data contracts
`GET /api/v1/orgs/{orgId}/entities/{entityId}/invoices/{invoiceId}/pdf` → `application/pdf`, filename
`invoice-<number or id>.pdf`

The page carries: the entity's legal name and the customer's name and billing address; the invoice number, issue
date, due date and terms; the lines with quantity, unit price and amount; the total, what has been paid and what is
still due; the memo; and a footer naming Solid.

## Rules
- The PDF is generated from the stored invoice each time; nothing is cached, so a reversal or a payment recorded a
  minute ago is reflected.
- Money is formatted from the stored minor units with the currency code beside it — never re-parsed from a string
  through a float.
- A draft invoice is watermarked **DRAFT** and says it is not yet issued; a void invoice is watermarked **VOID**.
  Handing someone a draft that looks final is how an invoice gets paid twice or not at all.
- Text is escaped for the PDF's encoding, and anything too long for its column is cut with an ellipsis rather than
  overflowing into the next column.
- The layout is deliberately plain (one font, no logo yet): a logo, colours and a template belong in a later slice
  with the branding work.

## Acceptance criteria
1. The endpoint returns a real PDF (starts with `%PDF`) with a `Content-Disposition` filename carrying the invoice
   number.
2. Extracted text contains the customer name, the invoice number, each line's description, and the total.
3. The amount due matches the invoice's `balanceDue` after a partial payment.
4. A draft is marked DRAFT; a voided invoice is marked VOID.
5. A member of another organization gets 404, and an anonymous caller 401.
6. A long description does not run into the amount column (the rendered line is truncated).
