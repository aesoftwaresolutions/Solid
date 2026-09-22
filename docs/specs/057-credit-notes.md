# 057 — Crediting a customer

**Status:** Done · **Owner review:** Needed · **CPA review:** Answered — see spec 058

## Goal
Today the only way to undo a charge is to void the whole invoice, which is wrong twice over: an invoice that
was really sent should not vanish, and half of it may have been right. A credit note says "we are taking
£X off what you owe", keeps the history of why, and lands in the ledger the moment it is issued.

## Scope
`billing` module. New tables `ar_ap.credit_note`, `ar_ap.credit_note_line` and `ar_ap.credit_application`,
the endpoints below, a card on the sales screen, and the arithmetic changes everywhere a customer balance is
worked out: the invoice's own balance, accounts-receivable aging, and the statement.

## The life of a credit note
`draft` → `issued`, with `void` as the other ending.

- A **draft** credit note posts nothing and is owed to nobody, exactly like a draft invoice.
- **Issuing** posts the mirror of an invoice: each line debits the income account it names, and the total
  credits accounts receivable. The customer owes less from that moment, whether or not the credit has been
  pointed at a particular invoice.
- **Applying** a credit to an invoice records which charge it offsets. It posts **nothing** — the ledger
  already moved when the credit was issued — but it is what makes an invoice read as settled.
- A credit can be applied across several invoices, and an invoice can carry several credits. Nothing may be
  applied beyond the credit's own total or beyond what the invoice still has outstanding.
- Unapplying is allowed and leaves the credit unspent. Voiding is a reversing entry, and is refused while any
  of the credit is still applied — unapply it first, so the reason is always deliberate.

## Data contract
- `POST/GET .../credit-notes`, `GET .../credit-notes/{id}`, `PATCH .../credit-notes/{id}` (draft only)
- `POST .../credit-notes/{id}/issue` → `issued`
- `POST .../credit-notes/{id}/void` → `void`
- `POST .../credit-notes/{id}/applications` `{invoiceId, amount}` → the credit note
- `DELETE .../credit-notes/{id}/applications/{applicationId}` → 204
- Credit note shape: `{id, customerId, customerName, creditNumber, issueDate, memo, total, status,
  applied, remaining, journalEntryId, lines: [...], applications: [{id, invoiceId, invoiceNumber, amount}]}`
- The invoice grows `creditsApplied`; its `balanceDue` is `total − amountPaid − creditsApplied`.

## Rules
- Credit numbers are generated per entity (`CN-0001`, …) the same way invoice numbers are.
- Only a `draft` credit note can be edited; only a `draft` can be issued; only an `issued` one can be applied
  or voided. Every refusal says what state it is in.
- A credit and the invoice it is applied to must belong to the same customer, and the invoice must be issued
  and not void.
- Over-applying is refused with `OVER_APPLIED`, whether it exceeds the credit's remainder or the invoice's
  outstanding balance.
- A fully credited invoice reads as `paid`; the money never arrived, but nothing is outstanding.
- Accounts-receivable aging and the customer statement both count credits, so the A/R report, the statement
  and the ledger's receivable account always agree.
- Entity-scoped; another organization gets 404.

## Acceptance criteria
1. A credit note can be created, edited while draft, and issued; issuing posts a balanced entry that debits
   the income account and credits receivables, and the ledger's A/R balance falls by the total.
2. Applying a credit to an invoice reduces that invoice's `balanceDue` and posts **no** further entry; the
   total of all journal entries is unchanged by the application.
3. A fully credited invoice reads as `paid`; a partly credited one still shows what is left.
4. Over-applying is refused — beyond the credit's remainder and beyond the invoice's balance — and applying
   to another customer's invoice is refused.
5. Unapplying restores both the credit's remainder and the invoice's balance.
6. Voiding an applied credit is refused; voiding an unapplied one reverses its entry and puts the A/R balance
   back.
7. Accounts-receivable aging and the statement both drop by the credit, so they agree with the ledger.
8. Another organization gets 404.

## Out of scope
Crediting sales tax — the CPA has since ruled on it, and spec 058 implements that ruling. Refunding a credit
in cash, vendor credits on the purchases side, and any automatic matching of credits to invoices.
