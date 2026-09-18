# 015 — Mileage log & home office

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed (rates and method rules)

## Goal
Capture the two deductions sole proprietors most often lose for lack of records: business mileage and the home office. Both produce a year-end figure the preparer can use, backed by a contemporaneous log.

## Scope
`deductions` module (`pf` schema): vehicles, mileage trips, home-office declarations, and two year-end reports.

## Why these don't post journal entries
Business mileage under the standard rate and the simplified home-office deduction are **tax deductions, not book expenses** — the money was never spent from the business bank account in that form. Solid records them as tax-input evidence and reports them; the amounts reach a return through the tax rule pack, not the general ledger. Actual vehicle and home expenses that *were* paid from the business still go through normal bookkeeping.

## Rate data (sourced, never guessed)
- `tax-rules/standard-mileage-rates.json`: IRS standard mileage rate per tax year, with the source note. Years the IRS has not announced are absent — the report then reports miles only and says the rate is unknown.
- `tax-rules/home-office-simplified.json`: the simplified method's rate per square foot and the maximum square footage, with the source note.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST/GET /vehicles` `{name, description?, inServiceDate?}`
- `POST /mileage-trips` `{vehicleId, tripDate, miles:"12.4", purpose, category: business|commuting|personal|charity|medical, startLocation?, endLocation?}`
- `GET /mileage-trips?taxYear=2026&category=business` · `DELETE /mileage-trips/{id}`
- `GET /reports/mileage?taxYear=2026` → `{taxYear, rateKnown, ratePerMile, businessMiles, commutingMiles, personalMiles, otherMiles, estimatedDeduction, source, note, byVehicle:[…]}`
- `PUT /home-office?taxYear=2026` `{method: "simplified", totalHomeSquareFeet, officeSquareFeet, monthsUsed?}` · `GET /home-office?taxYear=2026`
- `GET /reports/home-office?taxYear=2026` → deduction, the rate used, the square feet counted, business-use percentage, and the note

## Acceptance criteria
1. Miles are decimal with one decimal place, greater than zero, at most 10,000 per trip; trip date required; purpose required for business trips (409 `PURPOSE_REQUIRED`) because the IRS expects a contemporaneous record of business purpose.
2. Mileage report totals miles by category for the tax year and computes `estimatedDeduction = businessMiles × ratePerMile` rounded HALF_UP to cents, e.g. 1,000.0 business miles at 0.70 = 700.00.
3. For a tax year with no published rate, `rateKnown` is false, `estimatedDeduction` is null and the note says Solid will not guess; miles still total correctly.
4. Home office (simplified): deduction = min(officeSquareFeet, maximum from the data file) × rate per square foot, prorated by `monthsUsed / 12` when given; office square feet must be > 0 and ≤ total home square feet (400 sq ft office in a 2,000 sq ft home → 300 × 5.00 = 1,500.00 with the published cap).
5. Saving the same tax year twice replaces the declaration (idempotent `PUT`), and each tax year is independent.
6. Only `simplified` is accepted for now; `actual` returns 400 with a message saying it needs the tax rule pack (410-style explanation, no invented percentages).
7. Reports are per entity and respect org membership; viewers can read but not write.
8. RLS on both tables.

## Out of scope
Actual-expense vehicle method (depreciation, lease inclusion), actual-expense home office, GPS import, per-diem travel.
