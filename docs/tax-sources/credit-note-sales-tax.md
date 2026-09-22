# Sales tax on a credit note

**Source:** ruling from the owner's CPA, given 2026-09-22, recorded verbatim below.
**Used by:** spec 058, `CreditNoteService` (the tax on a credit note line).

## The ruling, verbatim

> Full Credit: Reverse 100% of the tax originally charged. The credit note must show the exact original tax
> amount as a negative value, bringing the net tax owed for that transaction to zero.
>
> Partial Credit: Reverse only the portion of tax that applies to the credited amount. Multiply the credited
> item amount by the original tax rate. Only that specific tax amount is refunded.

## What Solid does with it

- A credit note line names the invoice line it credits. The **original** line is where both the rate and the
  charged tax come from.
- Credited amount equals the original line's amount → the tax reversed is the original line's stored tax
  amount, exactly, not a recomputation. This is what makes the net tax on the transaction land on zero even
  when the original charge rounded a cent.
- Credited amount is less than the original → credited amount × the original line's rate, on minor units,
  rounded half-up: the same arithmetic the invoice used when it charged the tax.
- The rate is read from the rate the invoice line used, whatever its state today. A retired or superseded rate
  is still the rate that governs undoing a charge made under it, and Solid deliberately skips the "is this
  rate currently effective" check that applies when charging tax for the first time.
- A credit line that names no original line carries no tax: there is no charge for it to reverse.

## Not covered by this ruling

Refunding the credit in cash, returns of inventory, use tax, and any state-specific limit on how long after a
sale tax may be reversed. None of these are implemented; do not infer them from the above.
