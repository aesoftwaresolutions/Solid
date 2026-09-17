---
paths:
  - "packs/**"
  - "backend/src/**/tax/**"
  - "docs/tax-sources/**"
---
# Tax engine & rule pack rules
- Tax values live in rule packs (data), never hard-coded in Java.
- Every table value needs a `source` citation (IRS form/instructions, Rev. Proc., state DOR doc) that exists in docs/tax-sources/.
- Provisions with sunset years must declare `validYears`; engine must refuse to apply them outside that range.
- Missing or uncertain value → `TODO` + failing pack validation test. Do not guess from memory.
- Each pack change needs scenario tests (inputs → expected form lines). Expected values are provided by the owner/CPA, not derived by running the code.
- Returns are pinned to the pack version used; never silently recompute a filed return.
