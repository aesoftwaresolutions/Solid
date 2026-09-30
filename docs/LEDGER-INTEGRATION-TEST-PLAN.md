# Ledger integration test plan

Extend `JournalDatabaseRulesTests` or existing API test classes. Keep each test deterministic except for bounded concurrency scheduling.

## Parallel journal posting

Concurrently submit valid postings for one organization. Assert accepted entries have unique posted identities/sequence values, no duplicate audit-chain predecessor positions, balanced posted totals, and no partial headers or lines after conflicts.

## Idempotent posting retry

Replay one externally identified successful posting command. Assert a second request returns the defined replay outcome, creates exactly one posted journal, records only the appropriate audit event, and rejects the same key with materially different content.

## Period-lock race

Start a posting, lock its period in another transaction, then commit. Assert the outcome is deterministic, no partial posting remains, and the rejection is audit-visible where domain policy requires it.

## Cross-organization denial

Create equivalent records in two organizations. As organization A, attempt custom-query, journal-line, audit, attachment, reversal, report, and export access to organization B. Every path must be denied or filtered.

## Reversal integrity

Post and reverse a valid entry. Assert the original remains immutable, reversal lines mirror required amounts, reports net correctly, and direct deletion or mutation of posted content is refused by the database path.

## Import deduplication

Import the same bank statement fixture twice in one organization. Assert no duplicate transaction, suggestion, or posted journal. Repeat across organizations and verify no cross-tenant linkage or deduplication leakage.

## Build discipline

Use real PostgreSQL testing for constraints, triggers, locking, and RLS. Keep transactional cleanup explicit. Name suites so Surefire inclusion patterns cannot silently omit them. Bound all retry and timeout loops.
