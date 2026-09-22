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

## Second ruling: which period the reversal falls in

Given 2026-09-22, recorded verbatim:

> You should reverse the tax in the period the credit note was issued, rather than reopening the period of the
> original sale. However, because tax authorities have different rules, this depends entirely on whether your
> accounting system handles this as an ongoing adjustment or an amended return.

**What Solid does with it (spec 059).** Solid is an *ongoing adjustment* system: a credit note's reversal
falls in the period the credit was issued, and no closed period is ever reopened or restated. That is forced
by a rule the project already holds — posted entries are immutable and corrections are reversing entries — so
it cannot quietly become something else.

Because the CPA's answer turns on which model the system is, the sales tax report now says which model this
is, shows tax charged and tax credited separately rather than only the net, and lists every credit issued in
the period whose original invoice belongs to an earlier one. If a state takes the amended-return view, that
list is the set of returns to amend. Solid does not decide which view applies, and does not file anything.

## Not covered by this ruling

Refunding the credit in cash, returns of inventory, use tax, and any state-specific limit on how long after a
sale tax may be reversed. None of these are implemented; do not infer them from the above.
