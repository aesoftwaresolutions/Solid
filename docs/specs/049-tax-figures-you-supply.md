# 049 — Tax figures you supply, with their source

**Status:** Done · **Owner review:** Needed · **CPA review:** Needed

## Goal
Solid refuses to invent tax figures, which is right, and the figures it does know live in files inside the
jar, which is wrong for a self-hosted product: when the IRS announces next year's mileage rate, nobody should
have to rebuild the application to use it. This slice lets whoever runs the installation add a year's figure
at runtime — and makes them cite where it came from.

## Scope
`tax` module: a `tax.figure` table, a small store other modules ask before their built-in files, and the
instance-administration endpoints and screen. Three figures are supported, because those are the three the
application actually uses:

| key | unit | used by |
|---|---|---|
| `mileage_rate_per_mile` | US dollars per mile | the mileage log (spec 015) |
| `home_office_rate_per_square_foot` | US dollars per square foot | the home-office deduction (spec 015) |
| `form_1099_nec_threshold` | US dollars | 1099-NEC candidates (spec 013) |

An unknown key is refused: a typo must not quietly create a figure nothing reads.

## How a figure is found
1. A figure added here for that key and year, not superseded — the most recently added one wins.
2. Otherwise the file shipped in the jar.
3. Otherwise **unknown**, and every screen keeps saying so. Nothing is ever interpolated from a nearby year.

## Rules
- A source is required and must say something: at least 30 characters, naming the IRS notice, publication or
  page it came from. A figure with no provenance is worse than no figure.
- Adding a figure for a key and year that already has one is refused unless the request says
  `supersede: true`, which keeps the old row (marked superseded, with who and when) rather than overwriting
  it. Nothing in this table is ever deleted.
- Values are decimals with a scale the key defines, parsed exactly — money and rates never touch a double.
- The year must be within the same 1913–2100 bounds the rule packs use.
- Only an instance administrator can add or supersede a figure, and every change is audited
  (`tax_figure_added`, `tax_figure_superseded`) with the key, year, value and source.
- Solid still does not check whether the figure is *right*. The screen says so, and the source is displayed
  everywhere the figure is used, so a preparer can check it in one look.

## Data contract
- `GET /api/v1/instance/tax-figures` → `[{id, key, taxYear, value, unit, source, note, addedAt, addedBy,
  supersededAt, inUse}]`, newest first.
- `POST /api/v1/instance/tax-figures` `{key, taxYear, value, source, note, supersede}` → the new figure.
- `GET /api/v1/instance/tax-figures/keys` → the keys this version understands, with their units and scales.

## Acceptance criteria
1. Adding a mileage rate for a year the jar does not know makes the mileage deduction compute with it, and
   the response cites the source that was given.
2. Before it is added, that year still reports "unknown" and no figure is guessed.
3. A figure added for a year the jar *does* know takes precedence, and the old built-in value is no longer
   used.
4. A second figure for the same key and year is refused without `supersede`, and with it the older row stays
   in the list marked superseded while the newer one is in use.
5. An unknown key, a bad number, a year out of bounds or a source shorter than 30 characters are all refused
   with a message saying which.
6. Only an instance administrator can add or list figures.
7. The audit trail records additions and supersessions, with the value and the source.

## Out of scope
Judging whether a figure is correct, mid-year rate splits (the 2022 case, still a TODO in the file), figures
for anything the application does not yet compute, and any state-level figure.
