# 021 — Tax rule pack registry and coverage

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Every tax figure in Solid lives in a data file with a written source (`backend/src/main/resources/tax-rules/`).
Make that discipline enforceable and visible: refuse to start if a figure has no source, and let anyone ask
"what does this installation actually know about tax year N, and what is missing?"

This is the foundation the projection engine (phase 2) will stand on. It deliberately adds **no tax figures**.

## Scope
`tax` module. Reads the existing rule files, validates them at startup, and exposes a read-only API. The modules
that already read those files (1099 thresholds, mileage and home-office rates) keep working unchanged — the files
stay the single source of truth.

## Data contracts
Every file in `tax-rules/` is a JSON object that must have:
- `source` — a sentence naming the IRS publication, notice or law the figures come from. Required, non-blank.
- zero or more figures carrying a `taxYear`, and/or an `appliesFromTaxYear` for a figure that stands until changed.
- an optional `todo` array listing what is knowingly missing.

API (any signed-in user):
- `GET /api/v1/tax/rule-packs` → `[{id, title, source, taxYears: [...], appliesFromTaxYear, todos: [...]}]`
- `GET /api/v1/tax/rule-coverage?taxYear=2026` →
  `{taxYear, packs: [{id, title, covered, reason, source, todos}], covered: n, missing: n, note}`

## Rules
- A file without a non-blank `source`, with a duplicated `taxYear` in the same list, or with a `taxYear` outside
  1913–2100 makes the application **fail to start**. A bad rule file is a correctness bug, not a warning.
- A pack "covers" a year when it lists that `taxYear`, or when its `appliesFromTaxYear` is that year or earlier.
- The API never infers a figure for an uncovered year and never interpolates between years. Uncovered is reported
  as uncovered, with the pack's own todos as the explanation.
- `note` on the coverage response repeats, in plain words, that missing figures must come from a reviewed source
  before anything is filed.
- Adding a rule file needs no code change: the registry reads whatever is in the folder.

## Acceptance criteria
1. The registry lists every file in `tax-rules/` with its id, title, source and the years it covers.
2. Coverage for a year the files know (e.g. the 1099 threshold's latest listed year) reports that pack as covered;
   a year no pack lists reports it as missing with the pack's todos.
3. A rule file with a blank or absent `source` is rejected with a message naming the file.
4. A rule file listing the same `taxYear` twice, or a year outside 1913–2100, is rejected.
5. A pack with `appliesFromTaxYear` covers that year and every later year, and not earlier ones.
6. The endpoints require a signed-in user; an anonymous caller gets 401.
7. No tax figure is duplicated in code: the registry reports what the files say, and the existing readers still
   return the same values from the same files.

## Out of scope
The projection/calculation engine itself, state rule packs, signed or downloadable rule packs, versioning a pack
across a filing season, and any new tax figures.
