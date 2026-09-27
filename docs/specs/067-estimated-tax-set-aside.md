# 067 — Quarterly set-aside worksheet (estimated tax)

**Status:** In progress · **Owner review:** Needed · **CPA review:** Needed (worksheet wording and constants)

## Goal
A sole proprietor's first surprise is the quarterly voucher. The books know the year's profit; the missing
piece is "so how much should I be saving?" This slice answers that with a worksheet, not a filing: it computes
self-employment tax from statute + the sourced wage base, adds an income-tax leg at a marginal rate the
person *supplies*, and hands back a per-quarter set-aside with due dates.

## Scope
`reporting` module (it already talks to `tax`): `EstimatedTaxService` + one GET endpoint. A new rule file,
`tax-rules/us-self-employment.json`, holds the statutory constants and the per-year Social Security wage
base, each row with its source. `TaxFigures.Key` gains `se_wage_base` so an administrator can supply a year's
base at runtime (spec 049's machinery, same precedence: runtime → shipped file → unknown).

## Data contract
`GET /api/v1/orgs/{orgId}/entities/{entityId}/reports/estimated-tax?taxYear=YYYY[&marginalRatePercent=NN]`
```json
{"taxYear":2026,"currency":"USD",
 "netProfit":{"amount":"2000.00","currency":"USD"},
 "netSelfEmploymentEarnings":{...},"seTaxApplies":true,
 "socialSecurityPart":{...},"medicarePart":{...},"selfEmploymentTax":{...},
 "deductibleHalfOfSeTax":{...},
 "wageBase":{"amount":"184500.00","currency":"USD"},"wageBaseKnown":true,"wageBaseSource":"SSA press ...",
 "incomeTaxEstimate":{...},"marginalRatePercent":24,"incomeTaxEstimated":true,
 "annualSetAside":{...},"quarterlyPayment":{...},
 "quarterlyDueDates":["April 15, 2026 (for income earned Jan–Mar)", ...],
 "notes":["Not included: the Additional Medicare Tax ..."]}
```

## Rules (all enforced by tests)
- SE earnings = net profit × 0.9235, on integer minor units, half-up. No profit, no tax.
- Under $400 of net SE earnings: no SE tax at all (IRC §6017), and the worksheet says so.
- Social Security part applies the year's wage-base cap only when a base for *that year* is on file (runtime
  figure or shipped file). Nothing is carried forward from a neighboring year; if the year is unknown the
  part is computed uncapped and the note says so in words.
- Medicare part has no cap. Additional Medicare Tax (0.9%) is not computed — it depends on household income,
  which one entity's books do not hold — and the notes say so.
- Half of SE tax is deducted before the income-tax leg (IRC §164(f)).
- The income-tax leg exists only when the caller supplies `marginalRatePercent` (0–100; anything else is a
  400). It is applied flat, after the SE half-deduction, and the response labels it the caller's own rate.
- Quarterly = annual ÷ 4, half-up. Due dates are the generic statutory ones, stated as text.
- Sources travel with the numbers: `wageBaseSource`, and the constants' source in the notes.
- Read access is member-level like every other report; a viewer may read it.

## Acceptance criteria (all golden, hand-computed — not re-derived in code)
1. Profit 2,000.00 in 2026, marginalRate 24: SE earnings 1,847.00; SS 229.03; Medicare 53.56; SE 282.59;
   half 141.30; income leg (2,000.00−141.30)×24% = 446.09; annual 728.68; quarterly 182.17.
2. Same fixture without the rate: `incomeTaxEstimated=false`, income leg 0.00, note explains.
3. Profit 200.00: `seTaxApplies=false`, SE tax 0.00, note cites §6017.
4. Profit 300,000.00 in 2026: SS capped at 184,500 → 22,878.00; Medicare on 277,050.00 → 8,034.45.
5. Profit 300,000.00 in 2099 (no base on file): `wageBaseKnown=false`, uncapped SS 34,354.20, note says so.
6. A runtime figure for `se_wage_base`/2026 of 190,000.00 outranks the shipped file: SS 23,560.00.
7. `marginalRatePercent=104` and `=-5` are 400s.
8. A viewer may read the worksheet; a stranger gets 401.

## Out of scope
Form 1040-ES voucher generation, state estimates, Additional Medicare Tax, QBI, safe-harbor rules (100%/110%
of prior year — needs last year's filed return, which Solid does not hold), PDF output, and the UI card
(a follow-up slice adds the Reports-page panel; this slice is API + tests).
