# 035 — Fixed assets and deduction screens

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Two features have had a working API and no screen since slices 014 and 015: fixed assets with their depreciation,
and the mileage log and home-office declaration. Finish them, and be careful about how their figures are presented
— these are the numbers people are most tempted to treat as tax advice.

## Scope
Frontend only. Two pages: **Assets** (list, add, run depreciation, dispose, schedule) and **Deductions** (vehicles,
trips, mileage report, home office).

## Rules
- Every server note is shown as written. The fixed-asset schedule is **book** depreciation, and the mileage and
  home-office figures are **estimates** from a published rate: the pages say so in the server's own words rather
  than a paraphrase of them.
- When the server says a year's rate is not on file (`rateKnown: false`), the page shows the miles and **no
  deduction figure at all** — not a zero, not last year's rate. A missing figure is reported as missing.
- Depreciation runs post real journal entries, so the page names what it will do before the button is pressed and
  shows what was posted afterwards.
- Disposal asks for the date, the proceeds and the account for the gain or loss, and shows the resulting entry.
- Money and mileage are typed as decimal strings and sent as the API expects them.

## Acceptance criteria
1. The assets page lists assets with cost, accumulated depreciation and book value, and adds one.
2. Running depreciation through a month posts and reports the entries created.
3. Disposing of an asset sends the date, proceeds and gain/loss account, and the asset then shows as disposed.
4. The asset schedule shows each month's amount and whether it has been posted, with the server's book-depreciation
   note displayed.
5. The deductions page logs a trip against a vehicle and lists trips for the year.
6. The mileage report shows business miles and, when the rate is known, the estimated deduction, the source and the
   note; when `rateKnown` is false it shows the miles and says the rate is not on file, with no figure.
7. The home-office form saves the declaration and the report shows the deduction, the source and the note.

## Out of scope
Tax depreciation (MACRS, §179, bonus), Form 4562 and 8829 themselves, GPS or mileage-app import, and actual-expense
home-office calculations.
