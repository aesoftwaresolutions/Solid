# 008 — Bank import, review queue & categorization

**Status:** Done · **Owner review:** Needed

## Goal
Get bank and credit-card activity into the books without typing: import a CSV/OFX/QFX file, review the transactions, pick (or accept a suggested) category, and have balanced journal entries created automatically.

## Scope
`bank` module: bank accounts linked to GL accounts, file parsers (CSV, OFX 1.x SGML, OFX 2.x XML/QFX), import batches with de-duplication, review queue, categorization rules, history-based suggestions, categorize / exclude / uncategorize. Uses the ledger's public `JournalService`.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST /bank-accounts` `{name, glAccountId, institution?, mask?}` — GL account must be an active non-header **asset with subtype `bank`** or **liability with subtype `credit_card`** of this entity.
- `GET /bank-accounts`
- `POST /bank-accounts/{bankAccountId}/imports` multipart `file` (+ optional CSV hints `dateColumn`, `descriptionColumn`, `amountColumn`, `debitColumn`, `creditColumn`, `dateFormat`) → `{batchId, format, parsed, imported, duplicates}`
- `GET /bank-transactions?status=new|categorized|excluded&bankAccountId=`
- `POST /bank-transactions/{id}/categorize` `{accountId, memo?}` → transaction with `journalEntryId`
- `POST /bank-transactions/categorize` `{items:[{id, accountId}]}` (bulk, all-or-nothing)
- `POST /bank-transactions/{id}/exclude` · `POST /bank-transactions/{id}/uncategorize`
- `GET/POST /categorization-rules` `{contains, accountId, priority?}` · `DELETE /categorization-rules/{id}`

Amounts follow the bank's perspective: **positive = money into the account**, negative = money out (OFX convention). The journal entry is `bank GL line = amount`, `category line = −amount`, which is correct for both bank (asset) and credit-card (liability) accounts.

## Acceptance criteria
1. OFX SGML (unclosed tags), OFX XML and QFX files parse `STMTTRN` records (FITID, DTPOSTED, TRNAMT, NAME, MEMO); CSV auto-detects common headers (Date/Posted Date/Transaction Date; Description/Payee/Name/Memo; Amount or Debit+Credit) and date formats `yyyy-MM-dd`, `MM/dd/yyyy`, `M/d/yyyy`, `MM/dd/yy`. Money parsing accepts `$1,234.56`, `(12.34)` and `-12.34`; never uses floating point.
2. Malformed files → 400 with the problem line number; files over 5 MB or 20,000 rows → 400.
3. Re-importing the same file creates **0** new transactions. OFX de-dupes on FITID; CSV on (date, amount, description, occurrence-within-file) so two identical charges on the same day in one file are both kept.
4. Suggestions: the highest-priority matching rule (case-insensitive "contains" on description) wins; otherwise the account used by the most recent categorized transaction with the same normalized description; otherwise none. Creating a rule refreshes suggestions for `new` transactions.
5. Categorizing posts a balanced journal entry (source `bank`, sourceRef = transaction id, date = posted date) and marks the transaction `categorized`. Categorizing twice → 409 `ALREADY_CATEGORIZED`. The category can't be the bank account's own GL account (409).
6. Bulk categorize is atomic: if one item fails, none are posted.
7. Uncategorize reverses the journal entry and returns the transaction to `new`. Exclude marks it `excluded` (only from `new`).
8. Categorizing into a locked period → 409 `PERIOD_LOCKED`; the transaction stays `new`.
9. RLS on all bank tables; viewer role can't import or categorize (from 007).

## Out of scope
Live bank feeds (Services Hub), transfers matching between two bank accounts, splits, AI categorization, reconciliation (009).
