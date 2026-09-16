# 04 — API Design

## 1. Style

- **REST + JSON**, OpenAPI 3.1 spec is the source of truth (generate TypeScript client for the React app and Java server interfaces).
- Base path: `/api/v1`. Organization scoping in path: `/api/v1/orgs/{orgId}/...`.
- Auth: session cookie (HttpOnly, SameSite=Strict) for the web app; OAuth2 client-credentials / personal API tokens for integrations.
- Money in JSON: `{"amount": "1234.56", "currency": "USD"}` — **string** decimal, never float.
- Dates: ISO-8601 (`2026-09-16`); timestamps UTC with `Z`.
- Pagination: cursor-based `?limit=50&cursor=...` → `{ data: [...], nextCursor }`.
- Idempotency: `Idempotency-Key` header required on POSTs that create financial records or submissions.
- Concurrency: `ETag` / `If-Match` on updates.
- Errors: RFC 9457 Problem Details.

```json
{
  "type": "https://docs.example.com/errors/unbalanced-entry",
  "title": "Journal entry does not balance",
  "status": 422,
  "detail": "Debits 1,200.00 ≠ credits 1,100.00",
  "errors": [{ "field": "lines", "code": "UNBALANCED" }]
}
```

## 2. Key endpoints (v1 sketch)

### Organizations & entities
| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/orgs` | List / create organizations |
| GET/POST | `/orgs/{orgId}/entities` | Entities |
| POST | `/orgs/{orgId}/entities/{entityId}/owners` | Add ownership link |

### Ledger
| Method | Path | Purpose |
|---|---|---|
| GET/POST | `/orgs/{orgId}/entities/{entityId}/accounts` | Chart of accounts |
| POST | `.../journal-entries` | Create draft or posted entry |
| POST | `.../journal-entries/{id}/post` | Post a draft |
| POST | `.../journal-entries/{id}/reverse` | Create reversal |
| PUT | `.../period-lock` | Lock through date |
| GET | `.../reports/profit-and-loss?from=&to=&basis=accrual` | Reports |
| GET | `.../reports/balance-sheet?asOf=` | Balance sheet |
| GET | `.../reports/tax-lines?taxYear=2026` | Ledger rolled up to tax lines |

Example create entry:
```http
POST /api/v1/orgs/7f.../entities/1a.../journal-entries
Idempotency-Key: 5c0b6f5e-...

{
  "entryDate": "2026-09-10",
  "memo": "Adobe subscription",
  "post": true,
  "lines": [
    { "accountId": "...6100", "amount": { "amount": "54.99", "currency": "USD" } },
    { "accountId": "...1010", "amount": { "amount": "-54.99", "currency": "USD" } }
  ]
}
```

### Banking
| Method | Path | Purpose |
|---|---|---|
| POST | `.../bank-connections` | Start aggregator link (returns Hub link token) |
| POST | `.../bank-accounts/{id}/imports` | Upload OFX/QFX/CSV (multipart) |
| GET | `.../bank-transactions?status=new` | Review queue |
| POST | `.../bank-transactions/{id}/categorize` | Confirm category → creates journal entry |
| POST | `.../bank-transactions:bulk-categorize` | Bulk |
| GET/POST | `.../categorization-rules` | Rules |
| POST | `.../reconciliations` | Start reconciliation |

### Tax
| Method | Path | Purpose |
|---|---|---|
| GET | `.../tax/projection?taxYear=2026` | Live estimate (federal + state), with breakdown |
| GET | `.../tax/estimated-payments?taxYear=2026` | Quarterly amounts, due dates, safe harbor |
| POST | `.../tax-returns` | Start a return (`jurisdiction`, `taxYear`, `returnType`) |
| GET | `/tax-returns/{id}/interview/next` | Next interview screen(s) |
| PATCH | `/tax-returns/{id}/facts` | Set fact values (`[{path, value}]`) |
| GET | `/tax-returns/{id}/forms/{formCode}` | Computed form values |
| GET | `/tax-returns/{id}/explain?path=/f1040/line16` | Explanation trace |
| GET | `/tax-returns/{id}/diagnostics` | Errors / warnings |
| GET | `/tax-returns/{id}/pdf` | Filing packet |
| POST | `/tax-returns/{id}/efile` | Submit (Phase 2+) |
| GET | `/tax-returns/{id}/efile/status` | Ack status |

### Documents & AI
| Method | Path | Purpose |
|---|---|---|
| POST | `.../documents` | Upload (receipt, W-2, 1099...) |
| POST | `.../documents/{id}/extract` | Queue AI extraction → returns job |
| GET | `/jobs/{jobId}` | Job status |

### Platform
`/auth/login`, `/auth/mfa/verify`, `/users`, `/roles`, `/audit-events`, `/admin/backups`, `/admin/updates`, `/webhooks`.

## 3. Instance → Hub API (internal)

| Method | Path | Purpose |
|---|---|---|
| GET | `/hub/v1/updates?app=1.4.2` | Available app releases & rule packs |
| GET | `/hub/v1/rule-packs/{jurisdiction}/{year}/{version}` | Download signed pack |
| POST | `/hub/v1/bank/link-token` | Aggregator link token |
| POST | `/hub/v1/bank/sync` | Pull transactions for connection |
| POST | `/hub/v1/efile/submissions` | Submit encrypted MeF/IRIS package |
| GET | `/hub/v1/efile/submissions/{id}` | Ack / rejection details |
| POST | `/hub/v1/ai/complete` | Consent-gated cloud LLM relay |

mTLS + license JWT; every request carries `Instance-Id` and `Idempotency-Key`.
