# 058 — Sales tax on a credit note

**Status:** Done · **Owner review:** Needed · **CPA review:** Ruling received (recorded below)

## Goal
Slice 057 left sales tax out of credit notes on purpose, because getting it wrong means over- or
under-reporting money that belongs to a state. The owner's CPA has now given the rule, so this slice
implements it.

## The ruling this slice implements
> **Full credit:** Reverse 100% of the tax originally charged. The credit note must show the exact original tax
> amount as a negative value, bringing the net tax owed for that transaction to zero.
>
> **Partial credit:** Reverse only the portion of tax that applies to the credited amount. Multiply the
> credited item amount by the original tax rate. Only that specific tax amount is refunded.
>
> — the owner's CPA, recorded in `docs/tax-sources/credit-note-sales-tax.md`

Two things follow from it, and both matter:

1. The tax reversed is worked out from the **original** rate — the one on the invoice being credited — not
   whatever rate is in force today. A rate that has since changed or been retired is still the right rate for
   undoing a charge made under it.
2. A **full** credit reverses the stored amount exactly rather than recomputing it, so a rounded cent on the
   original invoice cannot be left stranded.

## Scope
`billing` and `salestax`. A credit note line may now name the invoice line it is crediting; when that original
line carried tax, the credit carries the matching tax back out.

## How it works
- A credit note line may carry `invoiceLineId`. If it does, the line it names must belong to an issued invoice
  of the same customer.
- Tax on that credit line:
  - **Full** (the credited amount equals the original line's amount): the original line's tax amount, exactly.
  - **Partial**: credited amount × the original line's rate, half-up on minor units, the same arithmetic the
    invoice used.
  - The original line had no tax: no tax.
- A credit line with no `invoiceLineId` carries no tax. That is a goodwill credit, not a return, and there is
  no charge for it to reverse.
- A line may not be credited for more than it was charged, counting every credit note already issued against
  it (`OVER_CREDITED`). Crediting more tax than was collected is the failure this rule exists to prevent.
- Issuing posts: the income accounts debited, **each tax liability account debited** by the tax reversed, and
  receivables credited with the whole thing. The entity owes the state less by exactly the amount reversed.
- The sales tax report counts issued credit notes as negative taxable sales and negative tax collected, in the
  period the credit was issued.

## Data contract
- Credit note line gains `invoiceLineId`, `taxRateId` and `taxAmount`; the credit note gains `taxTotal`.
- `total` stays "what comes off what the customer owes" — the lines plus the tax reversed.

## Acceptance criteria
1. Crediting a taxed line in full reverses the exact tax the invoice charged; the net tax for that transaction
   is zero, to the cent, including where the original rounded.
2. Crediting half of a taxed line reverses half the tax, computed at the original rate.
3. The rate used is the original one even when that rate has since been retired or superseded — a credit
   against last year's invoice does not use this year's rate.
4. Issuing debits the tax liability account, so what the entity owes the state falls by the tax reversed.
5. The sales tax report shows the credit as negative taxable sales and negative tax, netting a fully credited
   taxed invoice to zero.
6. A credit line with no original line carries no tax, exactly as it did before this slice.
7. Crediting a line for more than it was charged is refused, counting credits already issued against it.
8. Credit notes with no tax behave exactly as slice 057 left them (its tests pass untouched).

## Out of scope
Refunding the customer in cash (the credit reduces what is owed; a refund is a payment going the other way and
is its own slice), returns of inventory, and use tax.
