# 055 — Sending the quote

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Slice 054 left the quote as a record in the app, which is no use for winning work: the customer has to be able
to read it. This is the quote on a page — the same plain, honest layout the invoice PDF uses, saying what is
offered, what it costs and how long the price stands.

## Scope
`billing` module: `GET .../quotes/{quoteId}/pdf`, a `QuotePdf` renderer, and a download link on the sales
screen. The drawing helpers the invoice PDF already had (text, right-aligned text, rules, truncation, the
WinAnsi clean-up and the watermark) move into one `Pdf` class that both renderers use, so a fix to either
benefits both.

## What the page says
- The entity's legal name, then **QUOTE** and its number.
- Who it is for, the date it was issued, and — when there is one — "This price holds until <date>".
- The lines, with quantity, unit price and amount, continuing onto further pages when there are many.
- The total, then the memo.
- One sentence of status, in plain words: that it is a quote and not a bill, that it was accepted, declined,
  or that it has become invoice <number>.
- A watermark for the states where mistaking the document would matter: `EXPIRED`, `DECLINED`, `INVOICED`.

## Rules
- A quote PDF is **never** an invoice: it says so, it has no due date, no terms, no payment instructions and
  no amount due. Nobody should be able to pay from it by accident.
- It prints whatever state the quote is in — a draft quote can be printed, and says it is a draft.
- The file is named `quote-<number>.pdf`.
- Money on the page comes from the stored amounts; nothing is recalculated for printing.
- Entity-scoped; another organization gets 404.

## Acceptance criteria
1. `GET .../quotes/{id}/pdf` returns a PDF whose text contains the quote number, the customer, each line
   description and the total.
2. The word "invoice" appears only where the quote has become one, and the page carries no due date, terms or
   amount due.
3. A quote with a `validUntil` prints "This price holds until …".
4. An expired quote, a declined one and a converted one each carry their watermark.
5. Two hundred lines produce continuation pages and no lost line.
6. Another organization gets 404.
7. The existing invoice PDFs are unchanged by the shared-helper refactor (their tests still pass untouched).

## Out of scope
Logos, colours and fonts (branding is its own slice), emailing it, and any signature or acceptance mechanism
on the document.
