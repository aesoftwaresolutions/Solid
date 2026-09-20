# 042 — Finding that one transaction

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
"There was a payment of about four hundred dollars to that printing company in the spring." Today the only way
to find it is to open each screen in turn. One search box should look everywhere at once.

## Scope
New `search` module holding the query, the result shape and a small interface, `SearchProvider`. Each module
that owns records implements it for its own tables — the search module never reads another module's tables, so
the boundaries in `docs/design/02-architecture.md` hold and a new record type becomes searchable by adding one
class next to it.

Searched today: journal entries, accounts, bank transactions, customers, invoices, vendors, bills, documents.

## How a query is read
- The text is matched case-insensitively anywhere in the useful fields (memo, description, name, number,
  reference, file name).
- If the text also reads as an amount — `420`, `420.00`, `$420`, `-420.00` — records whose amount matches
  **exactly** are included too, ignoring the sign, because a payment of 420.00 and a receipt of 420.00 are
  equally likely to be what the person is after.
- Nothing is guessed: no fuzzy matching, no "did you mean". A search that finds nothing says so.

## Data contract
`GET /api/v1/orgs/{orgId}/entities/{entityId}/search?q=...` →
```
{query, amountInterpreted, groups: [{kind, total, hits: [{kind, id, label, detail, date, amount, where}]}]}
```
- `kind` is one of `journal_entry`, `account`, `bank_transaction`, `customer`, `invoice`, `vendor`, `bill`,
  `document`.
- `where` is the screen that shows the record, so the result can be a link.
- `amountInterpreted` is the amount the text was read as, or null — so the person can see *why* a 420.00
  invoice came back for the query "420".

## Rules
- At most 10 hits per kind, with `total` saying how many there are, so a common word cannot pull the whole
  ledger into one response.
- A query shorter than two characters returns nothing rather than everything.
- Results are ordered newest first within a kind; records without a date come last.
- Row-level security still applies: the search runs inside the organization scope like every other read, and
  another organization gets 404 from the entity check.

## Acceptance criteria
1. A word in a journal entry's memo finds that entry, with its date and amount.
2. The same word in a bank transaction's description, a customer's name, an invoice number, a vendor's name and
   a document's file name finds each of those, each under its own kind.
3. Searching `420` finds the 420.00 invoice and the -420.00 bank transaction, and `amountInterpreted` says
   420.00.
4. A word that matches nothing returns empty groups, not an error.
5. More than ten matches of one kind are capped at ten, and `total` reports the real number.
6. A one-character query returns nothing.
7. Records belonging to another entity of the same organization do not appear.
8. Another organization gets 404.

## Out of scope
Full-text ranking, stemming and spelling correction, searching inside document contents (the vault is
encrypted, and decrypting every file to answer a search is not something this software will do quietly), and
saved searches.
