# 043 — Fixes from the second review

**Status:** Done · **Owner review:** Needed · **Security review:** Done (this slice is the result of one)

## Goal
A hostile review of slices 034–042 found eleven real defects. This slice fixes them and adds a regression test
for each, in the same spirit as slice 033.

What the review found *clean*, and which therefore stays as it is: no SQL injection anywhere in the new code
(every concatenation splices compile-time constants; all user text is bound), tenant isolation holds on every
new endpoint, the CSV commit really is one transaction, and the cash-flow allocation is exact.

## The fixes

1. **The importer said "ready" about files the books would refuse.** The plan checked the tax line existed but
   not that the account could carry it, and checked nothing about the shape of the code, name or subtype — so a
   preview could promise 300 rows and the commit die on a database constraint with a generic 409.
   `AccountService.rejectionReason` now answers "why would this account be refused?" in one place, and the
   importer asks it for every row. *(finding 1)*
2. **An archived parent account** passed the plan and failed the insert; it is now a reported problem. *(2)*
3. **The row limit was enforced after the whole file had been built.** A 19 MB paste was turned into millions
   of objects before being rejected. The reader now refuses an over-long string outright and stops one record
   past the limit. *(3)*
4. **The overview used the calendar year for every entity**, so an entity whose year ends in June showed a
   different profit here than on its own reports. With no period asked for, each line now uses that entity's
   own fiscal year, and carries the period it used so the page can print it. *(4)*
5. **The overview opened five transactions per entity**, so figures came from five different snapshots; it is
   now one transaction for the page. The entities page also renders the overview's error instead of silently
   dropping the card. *(5)*
6. **A search for `%` matched everything.** LIKE wildcards in the query are escaped, so `50%` finds the memo
   that says "50% deposit" and `%%` finds nothing. *(6)*
7. **Invoice and bill amount matching** now uses `abs()` like the other providers, keeping the promise the page
   makes. A bill total cannot be negative today; the predicate no longer depends on that staying true. *(7)*
8. **Account codes are matched exactly**, as the database's uniqueness rule does — `cash` and `CASH` are two
   accounts, and treating them as one silently dropped a row. Customers and vendors stay case-insensitive, but
   the skip message now quotes the existing record's own spelling so a near-duplicate is visible. *(8)*
9. **A vendor already in the books is skipped before its optional columns are checked**, so re-running an
   imported file is still a no-op after the account one of its rows pointed at has been archived. *(9)*
10. **The Import button stayed enabled after a failed commit**; a failed call now clears the stale preview. An
    unreadable file no longer fails silently. *(10)*
11. **Line numbers were wrong after any quoted field containing a newline** — every later problem pointed one
    line too high. The reader now tracks the physical line each record starts on. *(11)*

## Acceptance criteria
Each fix above has a test that fails against the previous code:
`ImportReviewFixTests` (1, 2, 3, 8, 9, 11), `OverviewTests.anEntityWhoseYearEndsInJuneGetsItsOwnYear` (4),
`SearchTests.percentIsTreatedAsTextRatherThanAsAWildcard` (6), `SearchTests.anAmountFindsABillOfThatSize` (7),
and the frontend tests for the import page and the entities page (5, 10).

## Not fixed here, and why
- `BankService.countUncategorized` skips the `orgs.getEntity` check its siblings make. Not exploitable (RLS and
  the entity filter return zero), so it is left for a tidying slice rather than churned now.
- Search results link to the screen that holds the record, not to the record itself. That is a feature, not a
  defect, and belongs in a slice of its own.
