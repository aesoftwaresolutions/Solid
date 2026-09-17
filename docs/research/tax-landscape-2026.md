# US Tax Landscape (as of September 2026)

> Planning notes only. Verify every number against IRS publications / Revenue Procedures before coding it into a rule pack.

## 1. One Big Beautiful Bill Act (OBBBA, signed July 2025)

The largest recent change set. Items that directly affect our rule packs:

### Individuals
| Item | Change |
|---|---|
| Standard deduction (TY2025) | $15,750 single / $31,500 MFJ, indexed afterward |
| SALT deduction cap | Raised to $40,000 for 2025, +1%/yr through 2029 (with income phase-down) |
| "No tax on tips" | Deduction up to $25,000 for listed tipped occupations, TY2025–2028 |
| "No tax on overtime" | Deduction up to $12,500 single / $25,000 MFJ, TY2025–2028 |
| Senior deduction | Extra $6,000 per qualifying individual 65+, TY2025–2028 |
| Estate/gift exemption | $15M per person ($30M couple) from 2026 |

### Businesses
| Item | Change |
|---|---|
| Bonus depreciation | 100%, property acquired & placed in service after Jan 19, 2025 |
| Section 179 | $2.5M limit for 2025 (~$2.56M for 2026, indexed) |
| QBI (§199A) | 20% deduction made permanent |
| Domestic R&D | Immediate expensing restored |

### Information reporting
| Item | Change |
|---|---|
| 1099-NEC / 1099-MISC | Threshold rises from $600 to **$2,000** for payments starting 2026, indexed from 2027 |
| 1099-K | Reverted to **$20,000 and 200 transactions** |

**Design implication:** many provisions are temporary (2025–2028). Rules must be tax-year-scoped with explicit sunset dates — never hard-coded.

## 2. IRS Direct File is gone

- Launched 2024; the IRS told states in Nov 2025 it will not be available for the 2026 filing season.
- The IRS released most of the codebase publicly (`github.com/IRS-Public/direct-file`) under **CC0 / public domain**, archived and "not for production."
- Valuable references for us: the **Fact Graph** (declarative XML knowledge graph that reasons over incomplete answers), plain-language tax logic, test scenarios, MeF XML generation, and the state data-export JSON.
- Because it's public domain, ideas *and code* can legally be reused in a proprietary product (it's Scala, so we'd port concepts rather than code).

## 3. FIRE → IRIS for information returns (1099s)

- **Nov 19, 2026, 3 p.m. ET:** last day to file through the legacy FIRE system.
- **From Jan 1, 2027:** IRIS (Information Returns Intake System) is the only electronic filing system for information returns. Tax year 2026 1099s must go through IRIS.
- **Design implication:** build 1099 e-file against IRIS only; do not implement FIRE's fixed-width format.

## 4. Individual & business e-file (MeF)

- Federal and most state returns are e-filed via **Modernized e-File (MeF)** using XML schemas + business rules published per tax year.
- Software must pass **Assurance Testing System (ATS)** scenarios every year (Pub 5078 for business, Pub 1436 for individual).
- Transmission via **A2A** requires X.509 certificates from IRS-authorized CAs ("Strong Authentication").
- See `regulatory-compliance.md` for provider roles.

## 5. Sales tax

- Post-*Wayfair* (2018), every sales-tax state has **economic nexus** rules, commonly $100,000 in sales; several states have removed the older 200-transaction test.
- ~11,000+ local jurisdictions with changing rates → rates must come from a maintained data source (see technology-options).

## 6. Annual update cycle (drives our release calendar)

| When | IRS event | Our work |
|---|---|---|
| Oct–Nov | Inflation adjustments Rev. Proc. | Update brackets/limits in next-year rule pack |
| Oct–Dec | Draft forms, MeF schemas, ATS scenarios | Build forms + run ATS |
| Jan | Filing season opens | Ship rule pack release |
| Apr 15 | Individual deadline | Peak load / support |
| Sep 15 / Oct 15 | Extended business / individual deadlines | Extension support |
