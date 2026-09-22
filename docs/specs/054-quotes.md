# 054 — Quotes that become invoices

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Work is usually quoted before it is billed. Today the only way to send a price is to write an invoice for work
nobody has agreed to — which puts revenue in the books that may never happen. A quote should be a document you
can send, track, and turn into an invoice in one step when it is accepted, and it should touch the ledger
exactly never until then.

## Scope
`billing` module. New tables `ar_ap.quote` and `ar_ap.quote_line`, the endpoints below, and a card on the
sales screen.

## The life of a quote
`draft` → `sent` → `accepted` → `converted`, with `declined` and `expired` as the other endings.

- A quote **posts nothing**. It has no journal entry, appears in no report, and is owed by nobody. That is the
  whole point of it being a quote.
- Converting creates a **draft invoice** with the same customer, lines and memo, dated the day of conversion,
  and marks the quote `converted` with the invoice's id. The invoice is a draft because the numbers may need a
  last look before it goes.
- A quote can be converted once. A second attempt says which invoice it already became.
- `validUntil` is optional. Past it, a quote **nobody has answered** reads as `expired` and can be neither
  accepted nor converted until someone changes the date — a price you offered in March is not a price you owe
  in December. A quote that was accepted in time stays good: acceptance is the answer the date was waiting
  for.
- Declining is not deleting: the quote stays, with its reason, because the history of what you offered is
  worth keeping.

## Data contract
- `POST/GET/PATCH .../quotes`, `GET .../quotes/{id}` — a draft quote is editable like a draft invoice.
- `POST .../quotes/{id}/send` → `sent`
- `POST .../quotes/{id}/accept` → `accepted`
- `POST .../quotes/{id}/decline` `{reason}` → `declined`
- `POST .../quotes/{id}/convert` → the new draft invoice
- Quote shape: `{id, customerId, quoteNumber, issueDate, validUntil, memo, total, status, invoiceId, declinedReason, lines: [...]}`

## Rules
- Quote numbers are generated per entity (`Q-1001`, …) the same way invoice numbers are, and are unique
  within the entity.
- Only a `draft` quote can be edited; only `draft`/`sent` can be accepted or declined; only `accepted` can be
  converted. Every refusal says what state it is in.
- Lines carry the same fields an invoice line does, so conversion is a copy and cannot drift.
- A quote for an archived customer cannot be sent or converted.
- Everything is entity-scoped; another organization gets 404.

## Acceptance criteria
1. A quote can be created, edited while draft, sent, accepted and converted; the conversion returns a draft
   invoice with the same lines and total.
2. Converting twice is refused and names the invoice it already became.
3. Nothing about a quote appears in the profit & loss, the balance sheet or accounts receivable — before or
   after conversion, until the invoice itself is issued.
4. A quote past its `validUntil` that nobody answered reads as `expired` and can be neither accepted nor
   converted; one accepted before the date can still be converted afterwards.
5. A declined quote keeps its reason and cannot be converted.
6. Editing a sent quote is refused.
7. Quote numbers are unique per entity and sequential.
8. Another organization gets 404.

## Out of scope
A quote PDF (the invoice PDF is invoice-shaped; a quote needs its own layout and wording), acceptance by the
customer online, deposits and partial conversion, and any expiry that happens on a timer.
