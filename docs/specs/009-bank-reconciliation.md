# 009 — Bank reconciliation

**Status:** Done · **Owner review:** Needed

## Goal
Prove the books match the bank statement: for a statement period, tick off the ledger lines that cleared, see the difference, and lock in a completed reconciliation that can't silently change afterward.

## Scope
`bank` module: `bank.reconciliation` and `bank.reconciliation_line`; API to start, mark/unmark cleared lines, view status, complete, and undo the most recent reconciliation.

## Concepts
- **Cleared balance** = previous reconciliation's statement ending balance + sum of ledger lines marked cleared in this reconciliation (bank GL account lines only).
- **Difference** = statement ending balance − cleared balance. A reconciliation can be completed only when the difference is exactly 0.00.
- Amounts are from the bank's point of view (positive = money in), same as imports. For credit cards the statement balance is entered as a negative number when money is owed (e.g. owing $19.99 → `-19.99`).

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}/bank-accounts/{bankAccountId}`
- `POST /reconciliations` `{statementDate, statementEndingBalance: Money}` → 201 status
- `GET /reconciliations` → history · `GET /reconciliations/{id}` → status
- `GET /reconciliations/{id}/candidates` → uncleared posted ledger lines on the bank GL account dated ≤ statementDate: `{lineId, entryId, entryDate, memo, amount, cleared}`
- `POST /reconciliations/{id}/cleared` `{lineIds:[...], cleared: true|false}` → status
- `POST /reconciliations/{id}/complete` → status (409 `NOT_BALANCED` with difference)
- `POST /reconciliations/{id}/undo` → only the latest completed (or any in-progress) reconciliation; returns lines to uncleared

Status: `{id, statementDate, statementEndingBalance, beginningBalance, clearedBalance, difference, clearedCount, status: in_progress|completed, completedAt}`

## Acceptance criteria
1. Only one in-progress reconciliation per bank account (409 `RECONCILIATION_IN_PROGRESS`). Statement date must be after the previous completed reconciliation's date (409).
2. Beginning balance = previous completed reconciliation's ending balance, or 0.00 for the first.
3. Candidates include only posted lines on the bank's GL account, dated on/before the statement date, not cleared by a completed reconciliation. Reversal pairs appear as two lines that net to zero.
4. Marking lines updates cleared balance and difference exactly (Money arithmetic). Lines from other accounts/entities/orgs or dated after the statement → 409 `INVALID_LINE`.
5. Completing with difference ≠ 0 → 409 `NOT_BALANCED` (detail includes the difference). Completing with 0 sets status `completed`; its lines can't be cleared again by later reconciliations.
6. A completed reconciliation can't be modified (409 `RECONCILIATION_COMPLETED`), only undone if it is the latest. Undo is audited.
7. Golden scenario: import the September CSV fixture and categorize all six transactions. The checking account then holds 2,500.00 − 54.99 − 42.10 − 4.50 − 4.50 − 1,200.00 = **1,193.91**. Reconciling at 2026-09-30 with that statement balance gives difference 0 once every line is cleared; completing succeeds; the next reconciliation starts with beginning balance 1,193.91.
8. RLS on both tables; viewers can read but not change.

## Out of scope
Auto-matching statement lines to ledger lines (needs statement line import), adjustments entries, reconciliation reports PDF.
