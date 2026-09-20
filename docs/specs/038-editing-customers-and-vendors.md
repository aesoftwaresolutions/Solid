# 038 — Editing customers and vendors

**Status:** Done · **Owner review:** Needed

## Goal
The year-end checklist says "Contractor Co is missing a taxpayer ID and a classification" — and until now there was
no way to fix that without SQL. A customer or vendor's details must be editable, and archivable when they are no
longer used.

## Scope
`billing` module: update and archive for both customers and vendors, and the forms for them. Nothing about
invoices or bills changes; a name correction shows on old documents, which is what people expect of a name.

## Data contracts
- `PATCH /customers/{customerId}` `{name?, email?, phone?, billingAddress?, notes?, archived?}` → the customer
- `PATCH /vendors/{vendorId}` `{name?, email?, phone?, address?, taxIdLast4?, taxClassification?, is1099Vendor?,
  defaultExpenseAccountId?, archived?}` → the vendor

Only the fields sent are changed; anything omitted is left alone.

## Rules
- **Only the last four digits of a taxpayer ID are ever stored**, and the field says so. A full SSN or EIN is not
  needed to know whether a W-9 is on file, and holding one is a liability, not a feature.
- A name cannot be blanked; an email must look like an email; a tax classification must be one the API already
  knows.
- Archiving hides a customer or vendor from the pickers without touching their history. An archived one can be
  brought back.
- A default expense account must be a postable expense account of the same entity.
- Every change is audited (`customer_updated`, `vendor_updated`) with the field names — never the values, because
  the values include a taxpayer id fragment and an address.

## Acceptance criteria
1. Patching a vendor's tax id and classification fills exactly those fields and leaves the rest as they were.
2. After that, the 1099 report no longer lists those items as missing.
3. Patching a customer's name and address updates them, and the change shows on a statement.
4. Archiving a vendor keeps it out of the vendor list used for new bills but leaves its bills alone;
   un-archiving brings it back.
5. A blank name, a bad email, an unknown classification, an account from another entity, or a taxpayer ID longer
   than four digits are all refused with 400.
6. The audit log records that a vendor was updated and which fields — and does not contain the values.
7. Another organization gets 404.

## Out of scope
Merging duplicates, contacts and multiple addresses per customer, storing a full taxpayer ID (deliberately), and
generating the 1099 forms themselves.
