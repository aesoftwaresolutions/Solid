# Solid — Business & Personal Accounting + US Tax

An all-in-one, **self-hosted** accounting and tax platform for small businesses, sole proprietors, and households, built by **AE Software Solutions**.

> **Status:** Research & planning (Phase 0). No production code yet.
> **Disclaimer:** Tax figures in these docs are planning notes, not tax advice. Every tax rule must be verified against IRS/state primary sources before implementation.

## Vision

One system that handles the full money lifecycle for a person *and* the businesses they own:

- **Books** — double-entry general ledger, bank feeds, reconciliation, invoicing, bills, receipts
- **Personal finance** — household accounts, budgets, investments, W-2/1099 income tracking
- **Tax** — year-round tax estimates, federal + state income tax prep (1040, Schedule C/E/SE, 1120-S, 1065), sales tax, 1099 issuing, quarterly estimates
- **Filing** — PDF form output first; IRS MeF e-file and IRIS information-return e-file in a later phase
- **Local AI** — transaction categorization and receipt extraction via Ollama, so taxpayer data never has to leave the client's server

## Key decisions so far

| Topic | Decision | Doc |
|---|---|---|
| Delivery | Self-hosted per client (Docker Compose on a VPS), plus an optional AE-run "Services Hub" | [ADR-0001](docs/adr/0001-self-hosted-with-services-hub.md) |
| Jurisdictions | US federal + all states; architecture ready for other countries | [Requirements](docs/design/01-requirements.md) |
| Stack | Java 21 + Spring Boot, PostgreSQL, React + TypeScript | [ADR-0002](docs/adr/0002-tech-stack.md) |
| Ledger | Immutable double-entry journal, integer minor units | [ADR-0003](docs/adr/0003-ledger-model.md) |
| Tax rules | Declarative, versioned tax-year rule packs (inspired by IRS Direct File's Fact Graph) | [ADR-0004](docs/adr/0004-tax-rules-engine.md) |
| E-file | Phase 2+, transmitted centrally through the Services Hub | [ADR-0005](docs/adr/0005-efile-via-hub.md) |
| AI | Local-first (Ollama); cloud LLMs opt-in with §7216 consent | [ADR-0006](docs/adr/0006-ai-local-first.md) |

## Repository map

```
docs/
  research/
    market-analysis.md        Competitors, pricing, gaps
    tax-landscape-2026.md     OBBBA changes, FIRE→IRIS, Direct File shutdown
    regulatory-compliance.md  IRS e-file, FTC Safeguards, §7216, Pub 4557
    technology-options.md     Open-source ledgers, tax engines, bank feeds, sales tax APIs
  design/
    01-requirements.md        Functional / non-functional requirements
    02-architecture.md        Components, deployment, data flow
    03-data-model.md          Core schema (ledger, entities, tax)
    04-api-design.md          REST API conventions & key endpoints
    05-tax-engine.md          Rules engine deep dive
    06-security.md            Security architecture & compliance mapping
    07-roadmap.md             Phased plan & milestones
    08-open-questions.md      Decisions still needed
  adr/                        Architecture Decision Records
SOURCES.md                    All research sources
```

## Next steps

See [Roadmap](docs/design/07-roadmap.md) and [Open Questions](docs/design/08-open-questions.md).
