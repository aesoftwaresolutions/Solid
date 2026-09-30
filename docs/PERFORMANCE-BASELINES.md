# Performance baselines

Use this procedure before changing ledger, bank-review, reporting, exports, search, or document-vault queries. Accounting tables grow monotonically, so small development datasets can hide poor access paths.

## Required endpoint coverage

Measure dashboard summary, general ledger and journal list pages, trial balance, profit and loss, balance sheet, AR/AP aging, bank review queue, recurring preview/post, search, CSV/PDF exports, document listing, and document download.

## PostgreSQL evidence

For each measured read, capture the exact production-shaped query using:

```sql
explain (analyze, buffers, verbose)
-- exact application query here;
```

Run once with a cold process and again with warmed relevant buffers. Do not globally disable sequential scans for diagnostics. Store non-sensitive query-plan summaries with the change. Verify index proposals with `pg_stat_statements`, `EXPLAIN (ANALYZE, BUFFERS)`, and write-amplification review before merging.

## Current engineering budgets

- Dashboard summary: 500 ms target, 1 second investigation threshold.
- Paginated list: 500 ms target, 1 second investigation threshold.
- Standard report first page: 1 second target, 3 seconds investigation threshold.
- CSV stream start: 1 second target, 3 seconds investigation threshold.
- Async PDF job enqueue: 500 ms target, 1 second investigation threshold.
- Search response: 500 ms target, 1 second investigation threshold.

Treat these as gates, not customer service commitments. Changes that intentionally exceed a threshold require documented rationale and a follow-up owner.
