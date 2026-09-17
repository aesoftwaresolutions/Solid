# 07 — Roadmap

Dates assume 1–2 developers and are rough. Tax season dates are fixed and drive everything.

## Phase 0 — Research & foundations (Sep–Nov 2026)
- [x] Market, regulatory, tech research (this repo)
- [x] System design & ADRs
- [ ] Validate with 5–10 target customers (sole props + one small firm)
- [ ] Engage a CPA/EA advisor for rule-pack review
- [ ] Talk to an attorney: §7216 consent, ToS, privacy policy, licensing
- [ ] Skeleton repo: Spring Boot + React + Postgres + Docker Compose, CI, license scan
- [ ] Coding standards, money type, error format, OpenAPI pipeline

## Phase 1 — Books MVP (Dec 2026–Apr 2027)
Goal: a sole proprietor can run their business books on Solid.
- Identity (MFA), orgs/entities, RBAC, audit log
- Chart of accounts templates mapped to Schedule C lines
- Journal, period locks, P&L, balance sheet, trial balance
- OFX/QFX/CSV import, review queue, rules, reconciliation
- Invoices & bills (basic), receipts vault
- Installer + backups + upgrades on Hostinger VPS
- **Tax-line report** ("hand this to your preparer") for TY2026 filing season
- Pilot with 3–5 friendly customers

## Phase 2 — Tax projections & personal (May–Sep 2027)
- Rule engine + US-2026 and US-2027 federal packs (1040, Sch 1/2/3, C, SE, 8995)
- Live tax projection dashboard + quarterly estimates
- Personal accounts, W-2/1099 document import with local AI extraction (Ollama)
- Mileage, home office, fixed assets & depreciation
- Services Hub v1: licensing, signed updates, Teller/SimpleFIN bank feeds
- 1099-NEC tracking ($2,000 threshold) + PDF 1099s

## Phase 3 — Return preparation (Oct 2027–Apr 2028)
- Interview UI + forms mode + diagnostics + explanation trace
- Sch A, B, D, E, 8949, 4562, 8829; carryforwards
- First state packs (founding customers' states)
- PDF filing packets for TY2027 (paper file / preparer handoff)
- Oracle testing vs PolicyEngine in CI
- **Start IRS e-file application** (e-Services, Pub 3112, ETIN) — long lead time
- IRIS TCC application

## Phase 4 — E-file & business returns (2028)
- MeF A2A gateway in Hub; ATS for 1040 + states
- IRIS 1099 e-file
- 1120-S, 1065, K-1 flow-through to owner returns; Form 7004 extensions
- Sales-tax module (nexus, rates, liability reports)
- Accountant/firm features: multi-client dashboard, review & sign-off, 8879 e-signature

## Phase 5 — Scale & expand (2029+)
- Managed hosting / multi-tenant SaaS option
- More states & localities, multi-currency, inventory
- Payroll integration partner
- Public API & integrations marketplace (Stripe, Shopify, etc.)

## Milestone success metrics
| Milestone | Metric |
|---|---|
| Books MVP | Pilot users close 3 consecutive months with reconciled accounts |
| Projections | Projection within 5% of their actual filed liability |
| Return prep | 0 material errors in CPA review of 50 test returns |
| E-file | ATS passed; < 2% IRS rejection rate first season |
