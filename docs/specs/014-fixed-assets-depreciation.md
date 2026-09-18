# 014 — Fixed assets & book depreciation

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed (tax depreciation is deliberately not implemented)

## Goal
Track equipment and other long-lived purchases, spread their cost over their useful life in the books (straight-line), and record disposals — so the balance sheet and profit & loss are right and the preparer has a clean fixed-asset schedule.

## Scope
`assets` module (`fa` schema): assets, monthly straight-line depreciation runs, disposal, and a fixed-asset schedule report.

## Important limitation (deliberate)
This slice computes **book depreciation only** (straight-line). **Tax depreciation — MACRS, §179 and bonus depreciation — is not implemented**, because those need IRS tables and elections that belong in a reviewed tax rule pack (see CLAUDE.md: never invent tax figures). The fixed-asset report says so, and the tax-line report keeps showing book depreciation until the tax rule pack exists.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST/GET /assets` `{name, description?, category?, placedInServiceDate, cost, salvageValue?, usefulLifeMonths, assetAccountId, accumulatedAccountId, depreciationExpenseAccountId}`
- `GET /assets/{id}` → asset with `accumulatedDepreciation`, `netBookValue`, `monthlySchedule`
- `POST /depreciation-runs` `{throughMonth: "2026-12"}` → `{months:[{month, amount, journalEntryId}], totalPosted}`
- `POST /assets/{id}/dispose` `{disposalDate, proceeds, depositAccountId?, gainLossAccountId}` → posts the disposal entry
- `GET /reports/fixed-assets?asOf=2026-12-31` → per asset: cost, accumulated, net book value + totals + the tax-depreciation warning

## Rules
- Cost > 0, salvage ≥ 0 and < cost, useful life 1–600 months, placed-in-service date required.
- The depreciable base (cost − salvage) is split into `usefulLifeMonths` parts with `Money.allocate`, so the parts always add up exactly to the base; the leftover cents land in the **final** months, the usual bookkeeping convention.
- A run posts **one journal entry per month** (all assets combined): debit each asset's depreciation expense account, credit its accumulated depreciation account, dated the last day of the month, source `depreciation`.
- Months already posted are skipped (a run is idempotent); months in a locked period are skipped and reported.
- Disposal: debit accumulated depreciation recorded to date, debit proceeds to the deposit account (if any), credit the asset's cost, and post the difference as a gain (credit) or loss (debit) to the chosen gain/loss account. After disposal an asset is no longer depreciated.

## Acceptance criteria
1. $1,200 cost, no salvage, 36 months → 33.33 for the early months and 33.34 for the last twelve, with the 36 parts summing exactly to 1,200.00.
2. A run through 2026-12 for an asset placed in service 2026-10-01 posts 3 monthly entries (Oct, Nov, Dec), each dated the month end; running it again posts nothing.
3. Each posted entry balances and hits the configured expense and accumulated accounts; the trial balance stays balanced.
4. After 3 months, `netBookValue` = cost − accumulated; the fixed-asset report totals match the trial balance's asset and accumulated-depreciation balances.
5. Disposal for proceeds above net book value posts a gain; below, a loss. Selling the $1,200 asset after 3 months (accumulated 99.99, net book value 1,100.01) for 1,000.00 gives a loss of 100.01.
6. A second disposal, or depreciation after disposal, is refused (409 `ASSET_DISPOSED`).
7. Locked periods: months on or before the lock date are skipped and listed in `skippedMonths`; the rest still post.
8. Invalid inputs (salvage ≥ cost, life 0, missing accounts, wrong account types) are rejected with clear messages.
9. RLS on `fa` tables; viewers read-only.

## Out of scope
MACRS/§179/bonus (tax rule pack), partial-year conventions, revaluation, leases, asset transfers between entities.
