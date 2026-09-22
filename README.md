# Solid — Business & Personal Accounting + US Tax

An all-in-one, **self-hosted** accounting and tax platform for small businesses, sole proprietors, and households,
built by **AE Software Solutions**.

> **Status:** Phase 1 (Books) is built and tested. Tax *preparation* is not: Solid keeps the books, maps them to
> tax lines and hands your preparer a clean report. Nothing here is tax advice.

## What works today

Sign in (with mandatory two-factor), create an organization and its entities, and:

| Area | What you can do |
|---|---|
| **Books** | Chart of accounts from a template (Schedule C or household), manual journal entries, recurring entries, opening balances, period locks, immutable posting with a hash-chained audit trail |
| **Bank** | Import CSV/OFX/QFX, review queue with rules and bulk categorizing, reconcile against a statement, attach the receipt to the transaction |
| **Sales & purchases** | Customers, quotes that become invoices when accepted, invoices (draft → issued → paid), invoices that repeat for retainers, invoice and quote PDFs, statements, vendors, bills, payments, A/R and A/P aging, 1099-NEC candidate tracking |
| **Assets & deductions** | Fixed assets with straight-line book depreciation and disposal, mileage log, home-office declaration |
| **Personal** | Household chart of accounts, monthly budgets, budget vs actual |
| **Documents** | An encrypted vault for receipts and paperwork, linked to the records they support |
| **Reports** | Trial balance, P&L, balance sheet, statement of cash flows, what is scheduled for the next 90 days with the lowest point it reaches, tax-line report with a readiness check, year-end checklist, full CSV export |
| **Across entities** | One page for the whole organization: cash, net income and what is waiting, per entity, with a total only when the currencies match |
| **People** | Invitations with single-use, hashed, expiring links; roles that can be changed and access taken away, with the last owner protected; password change, administrator-issued resets, a command-line way back in, and a list of your own sessions you can end |
| **Getting started** | A setup list computed from the books themselves — what is done, what is next, what is genuinely optional — plus per-entity settings |
| **Search** | One box across journal entries, accounts, bank transactions, customers, invoices, vendors, bills and documents — by text or by exact amount |
| **Moving in** | CSV import of a chart of accounts, customer list or vendor list: previewed first, all-or-nothing, safe to re-run |
| **Tax figures** | Add a newly published rate or threshold at runtime, with the notice it came from — no rebuild, no guessing, and the source shown wherever the figure is used |
| **Operations** | Backups and a restore drill, a master-key guard that refuses to start against the wrong key, an instance page, a generated OpenAPI document |
| **Local AI (optional, off)** | Category suggestions from a model running on your own server |

Roughly 215 backend and 43 frontend tests cover the above; see `docs/specs/README.md` for the slice-by-slice
history, each with its acceptance criteria.

## Running it

```bash
cp .env.example .env          # then set SOLID_MASTER_KEY (openssl rand -base64 32)
docker compose up --build     # http://localhost:8080
```

The first account created becomes the instance administrator. Read **[docs/operations.md](docs/operations.md)**
before you put real books in it — especially the part about keeping `SOLID_MASTER_KEY` somewhere other than your
backups, because without it the encrypted documents cannot be read, by anyone, ever.

Developing:

```bash
cd backend && ./mvnw verify   # needs Docker (Testcontainers starts PostgreSQL 16)
cd frontend && npm test
```

The API is documented at `/swagger-ui/index.html` on a running server; **[docs/api.md](docs/api.md)** shows how to
script against it.

## The rules this codebase keeps

These are in `CLAUDE.md` and enforced by tests, not just good intentions:

- **Money is never a float.** Integer minor units in the database, `Money` in Java, decimal strings in JSON.
- **Tax figures are never invented.** Every rate, limit and threshold lives in a data file with its source, and a
  year with no published figure is reported as unknown rather than guessed (`/api/v1/tax/rule-coverage`).
- **Posted entries are immutable.** Corrections are reversing entries, enforced by database triggers.
- **Every organization's data is isolated** by PostgreSQL row-level security, with a test that fails if a new
  table forgets its policy.
- **Nothing is posted on a timer** and no figure ever comes from a language model.

## Key decisions

| Topic | Decision | Doc |
|---|---|---|
| Delivery | Self-hosted per client (Docker Compose on a VPS), plus an optional AE-run "Services Hub" | [ADR-0001](docs/adr/0001-self-hosted-with-services-hub.md) |
| Jurisdictions | US federal + all states; architecture ready for other countries | [Requirements](docs/design/01-requirements.md) |
| Stack | Java 21 + Spring Boot, PostgreSQL, React + TypeScript | [ADR-0002](docs/adr/0002-tech-stack.md) |
| Ledger | Immutable double-entry journal, integer minor units | [ADR-0003](docs/adr/0003-ledger-model.md) |
| Tax rules | Declarative, versioned tax-year rule packs | [ADR-0004](docs/adr/0004-tax-rules-engine.md) |
| E-file | Phase 2+, transmitted centrally through the Services Hub | [ADR-0005](docs/adr/0005-efile-via-hub.md) |
| AI | Local-first (Ollama); cloud LLMs opt-in with §7216 consent | [ADR-0006](docs/adr/0006-ai-local-first.md) |

## Repository map

```
docs/
  research/     Market, tax landscape, regulation, technology options
  design/       Requirements, architecture, data model, API, tax engine, security, roadmap
  adr/          The decisions above, with their reasoning
  specs/        One spec per slice, with acceptance criteria and status (start here)
  operations.md Backups, restore drill, upgrades
  api.md        Using the API from a script
backend/        Java 21, Spring Boot 3, Spring Modulith, Flyway, PostgreSQL 16
frontend/       React 19 + TypeScript + Vite
ops/            backup.sh, restore.sh
```

## What is deliberately not here yet

Tax **calculation** and filing: projections, 1040 and its schedules, state returns, e-file, and sales tax. Those
need reviewed rule packs and, for filing, IRS registration — the roadmap in
[docs/design/07-roadmap.md](docs/design/07-roadmap.md) lays out the order. Until then Solid's honest claim is that
it keeps good books and hands your preparer a clean, sourced report.
