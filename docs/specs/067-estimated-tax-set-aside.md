# 067 — Quarterly set-aside worksheet (estimated tax)

**Status:** In progress · **Owner review:** Needed · **CPA review:** Needed (worksheet wording and constants)

## Goal
A sole proprietor's first surprise is the quarterly voucher. The books know the year's profit; the missing
piece is "so how much should I be saving?" This slice answers that with a worksheet, not a filing: it computes
self-employment tax from statute + the sourced wage base, adds an income-tax leg at a marginal rate the
person *supplies*, and hands back a per-quarter set-aside with due dates.

## The shape of the answer: a worksheet, not a data bag
The response reads the way the paper Form 1040-ES worksheet reads: numbered steps, each with a label, an
amount, and the arithmetic that produced it ("2,000.00 × 92.35%"). A person can check every line by hand; a
preparer can disagree with any single step; nobody has to trust a bare total. Narrative steps (a missing
income-tax rate, a year under the threshold) appear as named lines with a null amount, so the page stays the
same shape however it ends.

## Scope
`reporting` module (it already talks to `tax`): `EstimatedTaxService` + one GET endpoint. A new rule file,
`tax-rules/us-self-employment.json`, holds the statutory constants and the per-year Social Security wage
base, each row with its source. `TaxFigures.Key` gains `se_wage_base` so an administrator can supply a year's
base at runtime (spec 049's machinery, same precedence: runtime → shipped file → unknown).

## Data contract
`GET /api/v1/orgs/{orgId}/entities/{entityId}/reports/estimated-tax?taxYear=YYYY[&marginalRatePercent=NN]`
```json
{"taxYear":2026,"currency":"USD",
 "steps":[
   {"line":1,"label":"Net profit this year, from your books","amount":{"amount":"2000.00","currency":"USD"},"note":"Your posted income minus your posted expenses for 2026."},
   {"line":2,"label":"Self-employment earnings","amount":{"amount":"1847.00","currency":"USD"},"note":"2000.00 × 92.35% — the law taxes only that share of profit (IRC §1402(a))."}
 ],
 "selfEmploymentTax":{"amount":"282.59","currency":"USD"},
 "incomeTaxIncluded":true,"marginalRatePercent":24,"incomeTaxEstimate":{"amount":"446.09","currency":"USD"},
 "annualSetAside":{"amount":"728.68","currency":"USD"},
 "quarterlyPayment":{"amount":"182.17","currency":"USD"},
 "wageBaseKnown":true,"wageBaseSource":"SSA press ...",
 "quarterlyDueDates":["April 15, 2026 (income earned Jan–Mar)", ...],
 "caveats":["Not included: the Additional Medicare Tax ...", "Rates and rules: IRC ..."]}
```

## Steps, in order
1. **Net profit** from the books (posted income minus posted expenses for the year). No profit → the
   worksheet ends there with "nothing to set aside".
2. **Self-employment earnings** = profit × 0.9235, integer minor units, half-up.
3. **Social Security part** = min(earnings, the year's wage base) × 12.4%. If no base is on file for that
   year (runtime figure first, shipped file second), the step says NO cap was applied, in words, on the line.
4. **Medicare part** = earnings × 2.9%, no cap.
5. **Self-employment tax** = the two parts. Under $400 of net SE earnings the answer is zero and the step
   cites IRC §6017.
6. **Half is deductible** (IRC §164(f)): SE tax ÷ 2.
7. **Income tax at the caller's rate** = (profit − the deductible half) × the rate they typed. No rate → the
   step is present with a null amount and the reason. A rate outside 0–100 is a 400.

Quarterly = annual ÷ 4, half-up. Due dates are the generic statutory ones, stated as text. `wageBaseKnown`
and `wageBaseSource` travel with the answer so any screen (or agent) can cite provenance.

## Acceptance criteria (all golden, hand-computed — not re-derived in code)
1. Profit 2,000.00 in 2026, rate 24: steps carry 1847.00 / 229.03 / 53.56 / 282.59 / 141.30 / 446.09 in
   order; annual 728.68; quarterly 182.17; each step's note names its arithmetic.
2. Same fixture without the rate: income-tax step present, amount null, note explains; `incomeTaxIncluded`
   false; annual 282.59.
3. Profit 200.00: SE step is 0.00 citing §6017; quarterly 0.00.
4. Profit exactly 0.00: a single "Nothing to set aside" line; quarterly 0.00.
5. Profit 300,000.00 in 2026: SS step 22,878.00 with the cap named in its note; Medicare step 8,034.45.
6. Profit 300,000.00 in 2099: `wageBaseKnown` false, the step says NO wage-base cap applied; SS step
   34,354.20.
7. A runtime `se_wage_base` figure of 190,000.00 outranks the shipped file: SS step 23,560.00.
8. Rate 104 or −5 → 400. A viewer may read it; a stranger gets 401.

## Out of scope
Form 1040-ES voucher generation, state estimates, Additional Medicare Tax, QBI, safe-harbor rules (100%/110%
of prior year — needs last year's filed return, which Solid does not hold), and PDF output. The UI card is
slice 068 (`frontend/src/pages/EstimatedTaxCard.tsx` — drop onto the Reports page).
