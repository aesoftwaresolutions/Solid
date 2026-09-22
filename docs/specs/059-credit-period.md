# 059 — Which period a credit belongs to

**Status:** Done · **Owner review:** Needed · **CPA review:** Ruling received (recorded below)

## Goal
Spec 058 reverses sales tax in the period the credit note was issued. The CPA has confirmed that, and added
the thing that matters more than the answer:

> You should reverse the tax in the period the credit note was issued, rather than reopening the period of the
> original sale. However, because tax authorities have different rules, this depends entirely on whether your
> accounting system handles this as an ongoing adjustment or an amended return.
>
> — the owner's CPA, recorded in `docs/tax-sources/credit-note-sales-tax.md`

So Solid must do two things it does not do today: **say out loud** which of the two models it implements, and
**show the user which credits would need amending** if their state takes the other view. A figure that is
right under one model and wrong under another is not something to leave silent in a report.

## Scope
`salestax`: the sales tax report. No change to how anything posts — spec 058's treatment is confirmed correct
and stays exactly as it is.

## What Solid is, stated plainly
Solid handles a credit note as an **ongoing adjustment**: the reversal falls in the period the credit note was
issued, and no closed period is ever reopened or restated. This follows from a rule the project already has —
posted entries are immutable, and corrections are reversing entries — so it is not a preference that could
quietly change.

## What the report gains
- Each jurisdiction shows **tax charged** and **tax credited** separately, as well as the net it already
  showed. A filing figure you cannot take apart is a filing figure you cannot check.
- A **prior-period adjustments** list: every credit issued inside the period whose original invoice was issued
  *before* the period began, with the credit's number and date, the original invoice's number and date, the
  jurisdiction, and the tax reversed. Under the ongoing-adjustment model these belong where they are. Under a
  state that wants an amended return, this list is exactly the set of returns to amend — that is what it is
  for, and the note says so.
- The note says which model Solid implements, and that a state may take the other view.

## Data contract
- `JurisdictionTotal` gains `taxCharged`, `taxCredited`, `taxableCharged`, `taxableCredited`. The existing
  `taxableSales` and `taxCollected` stay, still net, so nothing that reads them changes meaning.
- `SalesTaxReport` gains `priorPeriodAdjustments: [{creditNumber, creditDate, invoiceNumber, invoiceDate,
  jurisdiction, taxReversed, taxableReversed}]`.

## Acceptance criteria
1. A period with a charge and a credit shows both separately, and the net is unchanged from what the report
   showed before this slice.
2. A credit issued this period against an invoice from a previous period appears in `priorPeriodAdjustments`
   with both documents' numbers and dates, and its tax still counts in this period's net — Solid does not
   reopen the old period.
3. A credit against an invoice issued inside the same period does **not** appear in that list: there is
   nothing to amend.
4. The note states that Solid treats credits as an ongoing adjustment in the period the credit was issued,
   and that a state may instead require the original period's return to be amended.
5. The report still nets a fully credited invoice to zero, and spec 058's tests pass untouched.

## Out of scope
Producing an amended return, filing anything, and any state-by-state rule about which model applies — Solid
shows the figures and says what it did; which return to file is the filer's decision, with their CPA.
