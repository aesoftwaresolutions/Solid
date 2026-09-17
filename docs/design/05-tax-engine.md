# 05 — Tax Engine Deep Dive

The tax engine is the riskiest and most valuable part of the product. Goal: **tax law as versioned data, evaluated by a small, well-tested engine**, so yearly law changes are content updates, not code rewrites.

## 1. Concepts

| Concept | Description |
|---|---|
| **Rule pack** | Signed bundle for one jurisdiction + tax year: facts, formulas, tables, forms, interview flow, MeF mappings, tests |
| **Fact** | A named value in a graph, e.g. `/filingStatus`, `/income/w2[*]/wages`, `/f1040/line11_agi` |
| **Writable fact** | Supplied by user, document extraction, ledger, or carryforward |
| **Derived fact** | Computed by an expression over other facts |
| **Completeness** | Every fact is `complete`, `incomplete`, or `placeholder` — lets projections work with partial data (idea from IRS Direct File's Fact Graph) |
| **Table** | Brackets, limits, phase-outs, keyed by filing status |
| **Form map** | Fact → form line / PDF field / MeF XML element |
| **Interview** | Screens & questions with conditions on facts |

## 2. Rule pack format (sketch)

```yaml
# packs/US/2026/f1040/income.yaml
pack: US-2026
facts:
  - path: /income/w2[*]/wages
    type: money
    writable: true
    sources: [document:W2.box1, user]

  - path: /income/totalWages
    type: money
    derive: sum(/income/w2[*]/wages)

  - path: /f1040/line11_agi
    type: money
    derive: /f1040/line9_totalIncome - /sch1/line26_adjustments

  - path: /deductions/standard
    type: money
    derive: >
      table(standardDeduction, /filingStatus)
      + seniorAdditional()
    notes: "OBBBA amounts; verify against Rev. Proc. for TY2026"
    citations: ["IRC §63(c)", "Rev. Proc. 2025-XX"]

tables:
  standardDeduction:
    source: "Rev. Proc. 2025-XX"
    values: { single: TODO, mfj: TODO, mfs: TODO, hoh: TODO, qss: TODO }

sunsets:
  - path: /deductions/qualifiedTips
    validYears: [2025, 2028]
```

The expression language is intentionally small: arithmetic, `min/max/round`, `sum/count/filter` over collections, `if/then/else`, `table()`, `bracketTax()`, `phaseOut()`. No loops, no I/O. This keeps packs reviewable by a CPA who isn't a programmer.

## 3. Engine (Java)

```
RulePackLoader ─► verifies signature ─► parses YAML ─► builds DAG (detect cycles at load time)
FactStore      ─► writable facts from tax.fact_value + LedgerFactProvider + DocumentFactProvider
Evaluator      ─► lazy, memoized, topological evaluation; BigDecimal with explicit rounding rules
Explainer      ─► records dependency trace for "why is this number X?"
Diagnostics    ─► validation rules (missing, inconsistent, IRS business-rule pre-checks)
FormRenderer   ─► PDFBox fills IRS PDFs; MeF serializer builds XML via JAXB from IRS XSDs
```

- **Incremental recompute:** when a fact changes, invalidate only its downstream subgraph.
- **Rounding:** IRS whole-dollar rounding applied only where the form specifies; keep cents internally.
- **Determinism:** same inputs + same pack version ⇒ byte-identical output (tested).

## 4. Ledger → tax bridge

Each GL account has a default `tax_line_code`; lines can override. `LedgerFactProvider` sums posted lines by tax line for the entity's tax year (respecting cash vs accrual method) and exposes them as writable facts with `source=ledger`. Users can override with an adjustment fact (tracked & explained), never by editing the ledger silently.

Flow-through: `K1FactProvider` reads the partnership/S-corp return's K-1 facts × ownership % into the owner's return.

## 5. State taxes

- Each state is its own pack (`US-CA/2026`) that **imports** federal facts (AGI, etc.) read-only, then applies state adjustments.
- Build states in order of customer demand; start with no-income-tax states (trivial) plus the founding customers' states.
- Local taxes (NYC, Ohio municipalities, PA EIT) later.

## 6. Testing strategy

| Layer | Test |
|---|---|
| Engine | Unit + property-based tests (e.g., jqwik): DAG evaluation, rounding, phase-outs |
| Pack | Every pack ships YAML test scenarios (inputs → expected lines) |
| IRS ATS | Each year's ATS scenarios encoded as tests (required for e-file) |
| Oracle | CI job compares federal/state liability against **PolicyEngine US** and/or PSL Tax-Calculator run in a separate container (not linked → no AGPL issue) on thousands of synthetic households; diffs > $1 flagged |
| Golden files | PDF & MeF XML snapshot tests; XSD validation |
| Human review | CPA/EA sign-off checklist per pack release |

## 7. Pack lifecycle

```
draft (dev) ─► internal QA ─► CPA review ─► ATS passed (if e-file) ─► signed & published to Hub
     ─► instances download ─► returns pinned to pack version ─► patch versions (2026.1.1) may re-run with user notice
```
Returns record the pack version they were computed with; filed returns never silently change.

## 8. Build vs buy check

Commercial tax engines (e.g., licensed from a professional tax software vendor) could shortcut federal/state content but are expensive and usually SaaS-only. Revisit if state coverage becomes the bottleneck.
