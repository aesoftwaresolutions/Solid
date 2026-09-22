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
| 023 | Export everything (CSV bundle) | [023](023-data-export.md) | Done (owner review) | 005, 016 |
| 024 | Dashboard that says what to do next | [024](024-dashboard.md) | Done (owner review) | 010, 012, 013, 019, 021 |
| 025 | People and activity | [025](025-people-and-activity.md) | Done (owner + security review) | 007 |
| 026 | Recurring journal entries | [026](026-recurring-entries.md) | Done (owner review) | 005, 020 |
| 027 | Instance admin area | [027](027-instance-admin.md) | Done (owner review) | 018, 021 |
| 028 | Opening balances | [028](028-opening-balances.md) | Done (owner + CPA review) | 005 |
| 029 | API documentation (OpenAPI) | [029](029-api-documentation.md) | Done (owner review) | 007 |
| 030 | Category suggestions from a local model | [030](030-ai-category-suggestions.md) | Done (owner + security review) | 008 |
| 031 | Invoice PDF | [031](031-invoice-pdf.md) | Done (owner review) | 012 |
| 032 | Customer statements | [032](032-customer-statements.md) | Done (owner review) | 012, 031 |
| 033 | Fixes from the security and correctness review | [033](033-review-fixes.md) | Done (security review) | 016-032 |
| 034 | Reconciliation screen | [034](034-reconciliation-screen.md) | Done (owner review) | 009, 011 |
| 035 | Fixed assets and deduction screens | [035](035-assets-and-deductions-screens.md) | Done (owner + CPA review) | 014, 015 |
| 036 | Year-end checklist | [036](036-year-end-checklist.md) | Done (owner + CPA review) | 009, 010, 013, 014, 021 |
| 037 | Sales tax on invoices | [037](037-sales-tax.md) | Done (owner + CPA review) | 012 |
| 038 | Editing customers and vendors | [038](038-editing-customers-and-vendors.md) | Done (owner review) | 012, 013 |
| 039 | Statement of cash flows | [039](039-cash-flow-statement.md) | Done (owner + CPA review) | 006 |
| 040 | Bringing your existing books in (CSV import) | [040](040-csv-import.md) | Done (owner review) | 005, 012, 013 |
| 041 | The whole organization on one page | [041](041-organization-overview.md) | Done (owner review) | 006, 039 |
| 042 | Finding that one transaction (search) | [042](042-search.md) | Done (owner review) | 005, 008, 012, 013, 017 |
| 043 | Fixes from the second review | [043](043-second-review-fixes.md) | Done (security review) | 034-042 |

Statuses: Not started · Draft · Approved · In progress · In review · Done
