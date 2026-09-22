# 056 — The letterhead

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Every document Solid sends out — invoice, quote, statement — currently carries a legal name and nothing else.
A customer who wants to phone you, post you a cheque or check they are paying the right company has nowhere to
look. This slice gives an entity a letterhead: where it is, how to reach it, how to pay it, and its logo.

## Scope
`org` module owns the settings and the logo; `billing` reads them when it draws a document. One record per
entity, created empty, edited in one place, used by all three PDFs.

## What a letterhead holds
- `address` — the entity's own postal address, up to six lines.
- `phone`, `email`, `website` — shown on one line under the address, only the ones that are filled in.
- `taxId` — a free-text label such as "EIN 00-0000000", printed as given. Solid does not check it, and does
  not go looking for the entity's real one.
- `paymentInstructions` — up to 500 characters, printed on **invoices and statements only**. A quote is not a
  bill and must not tell anyone how to pay it.
- `logo` — a PNG or JPEG, at most 1 MB, uploaded separately and drawn at the top right, scaled to fit a box of
  180 × 60 points without distortion.

## Data contract
- `GET .../branding` → `{address, phone, email, website, taxId, paymentInstructions, hasLogo}`
- `PUT .../branding` — the whole record; any field may be null, which clears it.
- `PUT .../branding/logo` (multipart `file`) → `{hasLogo: true}`
- `DELETE .../branding/logo` → 204
- `GET .../branding/logo` → the image bytes with its own content type, 404 when there is none.

## Rules
- A file whose **first bytes** are not PNG or JPEG is refused with `UNSUPPORTED_LOGO`, whatever it is named.
  A file over 1 MB is refused with `LOGO_TOO_LARGE`. Both are 400.
- An entity with no letterhead prints exactly what it prints today; every field is optional and the documents
  must look right with all of them empty.
- `paymentInstructions` never reaches a quote. This is a rule, not a preference: spec 055 exists so that a
  quote cannot be mistaken for a demand for money.
- Entity-scoped; another organization gets 404. Only an owner or admin may change it; any member may read it.
- The logo is stored as bytes in the row. It is the entity's own mark, not a customer document, so it does not
  belong in the vault and is not encrypted with the document key.

## Acceptance criteria
1. A letterhead can be saved and read back; a second save with nulls clears the fields.
2. The invoice PDF shows the address, the contact line, the tax id and the payment instructions.
3. The quote PDF shows the address and contact line but **never** the payment instructions.
4. The statement PDF shows the address and the payment instructions.
5. An entity with no letterhead still renders all three documents, and their existing tests pass untouched.
6. A PNG logo is accepted and appears in the PDF (the page has an image); a text file named `logo.png` is
   refused with `UNSUPPORTED_LOGO`; a 2 MB file is refused with `LOGO_TOO_LARGE`.
7. A member who is neither owner nor admin cannot change the letterhead; another organization gets 404 for all
   of it.

## Out of scope
Colours, fonts and layout choices; per-customer branding; email signatures; putting the logo on screen in the
web UI beyond the settings page that manages it.
