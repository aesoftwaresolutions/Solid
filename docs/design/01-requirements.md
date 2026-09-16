# 01 — Requirements

## 1. Users & roles

| Persona | Needs |
|---|---|
| Household member | Track personal accounts, budgets, investments; prepare 1040 |
| Sole proprietor / freelancer | Books + invoicing + Schedule C + quarterly estimates |
| S-corp / partnership owner | Business books → 1120-S/1065 → K-1 → personal 1040 |
| Bookkeeper / accountant | Manage many client organizations; review & approve; close periods |
| Tax preparer (firm, EFIN holder) | Prepare, review, sign (Form 8879), e-file client returns |
| Instance admin | Install, back up, upgrade, manage users & security |

Roles (RBAC, scoped per organization and entity): `owner`, `admin`, `accountant`, `bookkeeper`, `preparer`, `reviewer`, `viewer`, `taxpayer`.

## 2. Functional requirements

### 2.1 Organizations & entities
- FR-1 One install hosts many **organizations** (a household, a firm's client).
- FR-2 An organization has many **entities**: individuals, sole props, LLCs, S-corps, C-corps, partnerships, trusts.
- FR-3 Ownership links between entities (e.g., Person A owns 60% of LLC B) drive K-1 and Schedule C/E flow-through.

### 2.2 Accounting core
- FR-10 Double-entry general ledger per entity, multi-book (cash & accrual views).
- FR-11 Chart of accounts templates per entity type, each account mapped to a **tax line** (e.g., Schedule C line 8 Advertising).
- FR-12 Journal entries are immutable once posted; corrections via reversing entries.
- FR-13 Period close / lock dates.
- FR-14 Bank & credit card import: OFX/QFX/CSV (v1), aggregator feeds (Teller/Plaid/SimpleFIN).
- FR-15 Bank reconciliation with match suggestions.
- FR-16 Rules-based + AI categorization with human confirmation.
- FR-17 Invoicing (A/R), bills (A/P), customers, vendors, payments.
- FR-18 Receipts & document vault attached to transactions.
- FR-19 Mileage and home-office tracking.
- FR-20 Fixed assets & depreciation (MACRS, §179, bonus).
- FR-21 Inventory (basic, COGS) — later phase.
- FR-22 Multi-currency — later phase (design data model for it now).
- FR-23 Reports: P&L, balance sheet, cash flow, trial balance, general ledger, A/R & A/P aging, budget vs actual.
- FR-24 Inter-entity transactions (owner contributions/draws, loans) posted to both entities consistently.

### 2.3 Personal finance
- FR-30 Personal accounts, net worth, budgets.
- FR-31 Investment holdings, cost basis lots, realized gains (feeds Form 8949 / Schedule D).
- FR-32 Import tax documents: W-2, 1099-INT/DIV/B/NEC/K/R, 1098, K-1 (manual entry + AI extraction + review).

### 2.4 Tax
- FR-40 Year-round **tax projection** for each person/entity from live books.
- FR-41 Quarterly estimated-tax calculator & reminders (1040-ES, state equivalents); safe-harbor logic.
- FR-42 Interview-style return preparation (guided questions) plus "forms mode" for pros.
- FR-43 Federal forms v1: 1040 + Schedules 1, 2, 3, A, B, C, D, E, SE; 8949; 8995 (QBI); 4562; 8829.
- FR-44 Federal business forms v2: 1120-S, 1065 + K-1s; 7004 extensions.
- FR-45 State income tax: rule-pack per state; start with the states our first customers live in.
- FR-46 Sales tax: nexus tracking, rates, liability report, filing worksheets per state.
- FR-47 1099-NEC/MISC issuance for vendors meeting the $2,000 threshold (TY2026+); W-9 collection.
- FR-48 Output: IRS-compliant PDF forms + printable filing packet.
- FR-49 E-file (Phase 2+): MeF federal/state, IRIS 1099s, extensions, acknowledgments, rejection handling.
- FR-50 Carryforwards (NOL, capital loss, §179, passive losses) tracked year to year.
- FR-51 Prior-year return import (PDF / previous-year data).
- FR-52 Diagnostics: missing info, audit-risk flags, "why is this number what it is?" explanation trace.

### 2.5 Platform
- FR-60 Audit log for every change and every read of sensitive data.
- FR-61 Data export (CSV, JSON, PDF) and full organization export.
- FR-62 Backups & restore built into the admin console.
- FR-63 Update channel for app releases and **tax rule packs** (signed).
- FR-64 Notifications: email + in-app (deadlines, estimates, bank-feed errors).
- FR-65 Public REST API with API keys / OAuth for integrations.

## 3. Non-functional requirements

| Category | Target |
|---|---|
| Deployment | Single `docker compose up` on a 2 vCPU / 4 GB RAM VPS for a small org; 8 GB+ recommended with local AI |
| Scale per install | Up to ~500 organizations, ~5M journal lines, ~50 concurrent users |
| Latency | p95 < 300 ms for ledger screens; full 1040 recompute < 2 s |
| Availability | Self-hosted: best effort with guided backups. Services Hub: 99.9%, and 99.95% during Jan–Apr filing season |
| Correctness | Ledger always balances (DB constraint); tax calcs match ATS scenarios and an independent oracle within $1 |
| Precision | No floating point for money. Integer minor units (cents) in DB, BigDecimal in Java |
| Security | MFA required; AES-256 at rest; TLS 1.2+; field-level encryption for SSN/EIN/account numbers (see 06-security) |
| Privacy | No taxpayer data leaves the install without explicit (§7216) consent |
| Auditability | Immutable, hash-chained audit and journal history |
| Retention | Default 7 years; configurable; legal-hold |
| Accessibility | WCAG 2.1 AA |
| Upgrades | Zero-data-loss migrations; rollback path; rule packs installable without app upgrade |
| Maintainability | Small team (1–3 devs); favor boring tech and a modular monolith |

## 4. Constraints & assumptions

- Team is small; founder is a beginner in Java/SQL/HTML → prioritize clear conventions, generated code, and strong tests over clever architecture.
- Hosting is Hostinger VPS for AE's own Hub and managed installs.
- US-only tax content at launch; currency USD but model supports others.
- Tax content accuracy is the biggest business risk — budget for a CPA/EA advisor to review each rule pack.
- E-file certification is a multi-month process that cannot be shortcut.

## 5. Out of scope (for now)

- Payroll processing (integrate with Gusto/Check later)
- Non-US tax
- C-corp 1120, trusts/estates (1041), exempt orgs (990)
- Native mobile apps (responsive web + PWA first)
