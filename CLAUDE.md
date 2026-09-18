# Solid — accounting & US tax platform (self-hosted)

Owner is a beginner in Java/SQL/HTML: explain non-obvious code, keep diffs small, and prefer simple, conventional patterns.

## Where things are
- Design (read before designing anything new): docs/design/, decisions: docs/adr/, research: docs/research/
- Slice specs and status index: docs/specs/ (work from a spec; update its status when done)
- Backend: backend/ (Java 21, Spring Boot 3, Spring Modulith, Flyway, jOOQ, PostgreSQL 16)
- Frontend: frontend/ (React + TypeScript + Vite)

## Commands
- Backend tests: `cd backend && ./mvnw verify`
- Frontend tests: `cd frontend && npm test`
- Run full stack: `docker compose up --build` → http://localhost:8080 (API on :8081 when run from IDE)
- Frontend dev server: `cd frontend && npm run dev` (proxies /api to :8081)
- Local stack needs a `.env` with `SOLID_MASTER_KEY` (copy `.env.example`)
- Backend tests need Docker running (Testcontainers starts PostgreSQL 16)
- Operator guide (backups, restore, upgrades): docs/operations.md; scripts in ops/
- Test classes must end in `Tests` (e.g. `MoneyPropertyTests`) or Maven Surefire silently skips them

## Non-negotiable rules
- IMPORTANT: Money is never float/double. Use the `Money` type (bigint cents in DB, BigDecimal in Java, string decimals in JSON).
- Never change expected values in existing tests, delete tests, or mark them skipped to make a build pass. Fix the code or ask.
- Never invent tax figures (rates, limits, brackets, thresholds). Use only cited sources in docs/tax-sources/ or rule packs; otherwise write TODO and fail validation.
- Posted journal entries are immutable; corrections are reversing entries.
- Flyway migrations are append-only: never edit a migration that exists on main; add a new one.
- No real PII in code, fixtures, logs, or commits. Use obviously fake test data.
- Respect module boundaries (see docs/design/02-architecture.md §3). Cross-module calls go through public interfaces or events.
- Only permissively licensed dependencies (MIT/BSD/Apache-2.0/CC0). No GPL/AGPL.

## Workflow
- Non-trivial change: spec (docs/specs/) → plan mode → tests first → implement → run checks → reviewer subagent.
- Show evidence of success: paste test/build output, don't just say it passes.
- Work on a feature branch; small commits with descriptive messages; open PRs with `gh`.
- When compacting, keep: current spec path, files changed, test commands, open TODOs.
