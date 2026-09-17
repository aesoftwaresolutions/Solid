# 002 — Money value type

**Status:** Done · **Owner review:** Needed

## Goal
One exact, immutable `Money` type used everywhere amounts appear, so floating-point and rounding bugs are impossible by construction.

## Scope
`backend/.../money/` (open Spring Modulith module usable by all modules), Jackson JSON support.

## Data contracts
- Java: `Money(long minorUnits, String currency)`; currency is an ISO-4217 code; minor units follow the currency's fraction digits (USD = cents).
- JSON: `{"amount": "1234.56", "currency": "USD"}` — amount is a **string** with exactly the currency's fraction digits.
- DB (future slices): `bigint` minor units + `char(3)` currency.

## Acceptance criteria
1. `Money.of("12.34", "USD")` has 1234 minor units; `Money.of("12.345","USD")` is rejected (too many decimals) unless a rounding mode is given.
2. `add`/`subtract` of different currencies throws `CurrencyMismatchException`.
3. Arithmetic overflow throws `ArithmeticException` (never wraps).
4. `multiply(BigDecimal factor, RoundingMode mode)` requires an explicit rounding mode; e.g. $10.00 × 0.0725 HALF_UP = $0.73.
5. `allocate(ratios…)` returns parts that always sum exactly to the original (largest-remainder method; ties go to earlier parts). E.g. $100.00 split 1:1:1 → $33.34, $33.33, $33.33. Works for negative amounts.
6. Property tests: for random amounts/ratios, allocation sums to the original and no part differs from its exact share by ≥ 1 minor unit; `a.add(b).subtract(b) == a`.
7. JSON round-trip preserves value; JSON numbers (floats) for `amount` are rejected.
8. `toString()` / `toDecimalString()` never use scientific notation.

## Out of scope
Currency conversion/FX rates, formatting with symbols/locales (UI concern).

## Decisions made without owner input
- Store minor units as `long` (max ≈ $92 quadrillion) — sufficient.
- Negative zero not representable; zero is zero.
