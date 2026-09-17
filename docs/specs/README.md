# Slice Specs Index

One row per slice. Workflow: `/write-spec` → review → `/clear` → plan mode → `/build-slice NNN` → review → merge. See [Claude Code playbook](../claude-code-playbook.md).

| # | Slice | Spec | Status | Depends on |
|---|---|---|---|---|
| 001 | Project skeleton (Spring Boot, Postgres/Testcontainers, React/Vite, Docker Compose, CI, license scan) | [001](001-project-skeleton.md) | Done (owner review) | — |
| 002 | Money value type | [002](002-money-type.md) | Done (owner review) | 001 |
| 003 | Organizations & entities + RLS | [003](003-organizations-entities.md) | Done (owner review) | 001 |
| 004 | Chart of accounts + tax-line codes (Schedule C template) | [004](004-chart-of-accounts.md) | Done (owner + CPA review) | 003 |
| 005 | Journal posting (balanced, immutable, period lock) | — | Not started | 002, 004 |
| 006 | Trial balance, P&L, balance sheet | — | Not started | 005 |
| 007 | Users, login, mandatory MFA, audit log | — | Not started | 003 |
| 008 | CSV/OFX import → review queue → categorize | — | Not started | 005 |
| 009 | Bank reconciliation | — | Not started | 008 |
| 010 | Tax-line report for preparers | — | Not started | 006 |

Statuses: Not started · Draft · Approved · In progress · In review · Done
