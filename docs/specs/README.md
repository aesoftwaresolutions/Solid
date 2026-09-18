# Slice Specs Index

One row per slice. Workflow: `/write-spec` → review → `/clear` → plan mode → `/build-slice NNN` → review → merge. See [Claude Code playbook](../claude-code-playbook.md).

| # | Slice | Spec | Status | Depends on |
|---|---|---|---|---|
| 001 | Project skeleton (Spring Boot, Postgres/Testcontainers, React/Vite, Docker Compose, CI, license scan) | [001](001-project-skeleton.md) | Done (owner review) | — |
| 002 | Money value type | [002](002-money-type.md) | Done (owner review) | 001 |
| 003 | Organizations & entities + RLS | [003](003-organizations-entities.md) | Done (owner review) | 001 |
| 004 | Chart of accounts + tax-line codes (Schedule C template) | [004](004-chart-of-accounts.md) | Done (owner + CPA review) | 003 |
| 005 | Journal posting (balanced, immutable, period lock) | [005](005-journal-posting.md) | Done (owner review) | 002, 004 |
| 006 | Trial balance, P&L, balance sheet | [006](006-financial-reports.md) | Done (owner review) | 005 |
| 007 | Users, login, mandatory MFA, audit log | [007](007-auth-mfa-audit.md) | Done (owner + security review) | 003 |
| 008 | CSV/OFX import → review queue → categorize | [008](008-bank-import-categorize.md) | Done (owner review) | 005 |
| 009 | Bank reconciliation | [009](009-bank-reconciliation.md) | Done (owner review) | 008 |
| 010 | Tax-line report for preparers | [010](010-tax-line-report.md) | Done (owner + CPA review) | 006 |
| 011 | Web UI (login/MFA, books, reports) | [011](011-web-ui.md) | Done (owner review) | 007, 010 |
| 012 | Customers, invoices & receipts (A/R) | [012](012-invoicing-ar.md) | Done (owner review) | 005 |
| 013 | Vendors, bills (A/P) & 1099 tracking | [013](013-vendors-bills-1099.md) | Done (owner + CPA review) | 005 |
| 014 | Fixed assets & book depreciation | [014](014-fixed-assets-depreciation.md) | Done (owner + CPA review) | 005 |
| 015 | Mileage log & home office | [015](015-mileage-home-office.md) | Done (owner + CPA review) | 003 |
| 016 | Receipts & document vault | [016](016-document-vault.md) | Done (owner + security review) | 007 |
| 017 | Web UI for sales, purchases and documents | [017](017-web-ui-sales-purchases-documents.md) | Done (owner review) | 012, 013, 016 |
| 018 | Backups, restore and instance identity | [018](018-backups-and-instance-identity.md) | Done (owner + security review) | 007, 016 |
| 019 | Personal accounts and monthly budgets | [019](019-personal-budgets.md) | Done (owner review) | 004, 006 |
| 020 | Budget and journal screens | [020](020-web-ui-budget-and-journal.md) | Done (owner review) | 011, 019 |
| 021 | Tax rule pack registry and coverage | [021](021-tax-rule-pack-registry.md) | Done (owner + CPA review) | 004 |
| 022 | Bank rules and receipts in the review queue | [022](022-bank-rules-and-receipts.md) | Done (owner review) | 008, 016 |

Statuses: Not started · Draft · Approved · In progress · In review · Done
