# Changelog

All notable changes to Solid are recorded here, newest first.
The format follows keepachangelog.com; versions use semver.

## [Unreleased]

### Added
- Slice 067: quarterly estimated-tax set-aside worksheet
  (`GET .../reports/estimated-tax?taxYear=YYYY[&marginalRatePercent=NN]`). SE tax from statute and the
  sourced wage base (runtime-overridable via a new `se_wage_base` tax figure); the income-tax leg exists only
  at the caller's own supplied marginal rate. A year with no wage base on file is reported as unknown, never
  carried forward.
- SECURITY.md with a vulnerability-reporting channel.
- Third-party license notices bundled with every desktop installer.
- MCP server (`mcp/`) so a local LLM agent can read the books and perform a small set of safe writes.

### Fixed
- Desktop remote-database slice: POSIX file tests now run on their intended OS only; a test referenced a
  helper that did not exist; remote connections now carry a socket timeout so a stalled server fails fast
  instead of hanging startup.
- pom.xml: malformed XML repaired; the last vendored-tooling reference removed from build comments.
- Stylesheet: the missing `.visually-hidden` rule restored (labels in the bank review queue had been rendering).

## [0.1.0] - 2026-09-26

First distributable build.

### Added
- Server install: Docker Compose stack (app, web via Caddy, PostgreSQL 16) with
  document store, backups, and operator guide (docs/operations.md).
- Desktop install: one-file installers (.msi, .dmg, .deb) bundling Java 21 and real
  PostgreSQL, loopback-only, single-instance, all data in one folder that is the backup.
- Desktop Settings can point a desktop install at a remote PostgreSQL server instead
  of the bundled one (test-before-save; takes effect on restart).
- Full bookkeeping: chart of accounts with tax-line mapping (Schedule C),
  double-entry journal with locking and reversal rules, quotes → invoices →
  payments → credit notes with correct sales-tax reversal, recurring entries and
  invoices, depreciation runs, bank import (CSV/OFX/QFX) with a review queue,
  reconciliation, financial and tax-line reports with CSV export.
- Identity: MFA with recovery codes, invitations, password change/reset, session
  management, per-address sign-in attempt limiting.
- Isolation between organizations by PostgreSQL row-level security, proven by tests
  on both server and desktop builds.
- Four formal review rounds of security, isolation and accounting-correctness fixes,
  each with regression tests.

### Known limitations
- Installers are unsigned; Windows and macOS will warn at first install. Signing and
  notarization arrive with the owner's certificates.
- No auto-update yet: install the newer version over the old one; data migrates.
