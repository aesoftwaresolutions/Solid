# Changelog

All notable changes to Solid are recorded here, newest first.
The format follows keepachangelog.com; versions use semver.

## [Unreleased]

### Added
- SECURITY.md with a vulnerability-reporting channel.
- Third-party license notices bundled with every desktop installer.

### Fixed
- Desktop remote-database slice: POSIX file tests now run on their intended OS only;
  a test referenced a helper that did not exist; remote connections now carry a socket
  timeout so a stalled server fails fast instead of hanging startup.

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
