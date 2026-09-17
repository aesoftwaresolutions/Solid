# 005 — Journal posting

**Status:** Done · **Owner review:** Needed

## Goal
Record double-entry journal entries that always balance, can't be altered once posted, respect period locks, and are tamper-evident. This is the heart of the books.

## Scope
`ledger` module: `gl.journal_entry`, `gl.journal_line`, `gl.period_lock`; posting, reversal, draft deletion, period lock and hash-chain verification APIs; database triggers that enforce the rules even if application code has a bug.

## Data contracts
API base: `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST /journal-entries` (optional header `Idempotency-Key`)
  ```json
  {"entryDate":"2026-09-10","memo":"Adobe subscription","post":true,
   "lines":[{"accountId":"…6220","amount":{"amount":"54.99","currency":"USD"},"memo":null},
            {"accountId":"…1010","amount":{"amount":"-54.99","currency":"USD"}}]}
  ```
  Positive = debit, negative = credit. → 201 entry with lines, `status`, `postingSeq` (when posted).
- `GET /journal-entries?from=&to=&status=` · `GET /journal-entries/{id}`
- `POST /journal-entries/{id}/post` (draft → posted)
- `POST /journal-entries/{id}/reverse` `{"entryDate"?: date, "memo"?: text}` → 201 new posted entry
- `DELETE /journal-entries/{id}` (drafts only) → 204
- `GET /period-lock` · `PUT /period-lock` `{"lockedThrough":"2026-06-30"}`
- `GET /journal/verify` → `{"valid":true,"postedEntries":12,"firstInvalidSeq":null}`

## Acceptance criteria
1. A posted entry has ≥ 2 lines, no zero lines, and lines sum to exactly zero (409 `UNBALANCED` / `TOO_FEW_LINES`; zero line → 400). Drafts may be unbalanced.
2. Every line's account belongs to the entity, is not a header, and is not archived (409 `ACCOUNT_NOT_POSTABLE`). Line currency must equal the entity's base currency (409 `CURRENCY_MISMATCH`).
3. Posted entries and their lines can't be updated or deleted — by the API (409 `ENTRY_POSTED`) **or** directly in SQL as the app role (database trigger error).
4. The database refuses to commit a posted entry whose lines don't sum to zero, even when inserted directly with SQL.
5. Entries dated on or before the entity's `lockedThrough` can't be posted, reversed into, or created (409 `PERIOD_LOCKED`); also enforced by trigger. Lock date can move forward or backward (backward = reopen; audited in slice 007).
6. Reversal creates a posted entry with every line negated, `reversesEntryId` set, default date = original date (or given date, which must not be locked). An entry can be reversed only once (409 `ALREADY_REVERSED`); drafts can't be reversed (409 `NOT_POSTED`).
7. Posted entries get a gap-free `postingSeq` per entity and a SHA-256 hash chained to the previous posted entry. `GET /journal/verify` returns `valid: true` for untouched data and reports the first invalid sequence if a posted row was tampered with at the database-owner level.
8. Same `Idempotency-Key` for the same entity returns the original entry (200) instead of creating a duplicate.
9. RLS on all three tables (coverage test from 003).

## Out of scope
Multi-currency/FX, attachments, recurring entries, audit log (007), balances/reports (006).

## Decisions made without owner input
- Signed single amount column (+debit / −credit), as in ADR-0003.
- Hash = SHA-256 over `prevHash | seq | entryId | date | memo | lines(line_no:account:amount:currency)`, hex-encoded.
- Draft deletion allowed (drafts aren't part of the books).
