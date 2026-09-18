# 011 — Web UI (login, books, reports)

**Status:** Done · **Owner review:** Needed

## Goal
Make everything built so far usable in a browser: sign in with MFA, set up an organization and entity, load a chart of accounts, import and categorize bank transactions, and read the reports.

## Scope
`frontend/`: React + TypeScript SPA served by Caddy in the same origin as the API (so the session cookie and CSRF token work without CORS).

## Screens
| Route | Purpose |
|---|---|
| `/login` | Email + password, then MFA (enroll on first login, otherwise verify). Recovery code accepted. |
| `/` | Organization list + create; pick one |
| `/orgs/:orgId` | Entity list + create; readiness summary per entity |
| `/orgs/:orgId/entities/:entityId` | Dashboard: this year's income/expenses/net, readiness checklist, quick links |
| `.../accounts` | Chart of accounts; apply the Schedule C template; see each account's tax line |
| `.../bank` | Bank accounts, statement upload, review queue: accept the suggestion or pick a category, exclude, uncategorize |
| `.../reports` | Trial balance, P&L, balance sheet, tax-line report with CSV download |

## Rules
- Auth uses the session **cookie** (HttpOnly) plus the `X-XSRF-TOKEN` header read from the `XSRF-TOKEN` cookie for unsafe methods — the SPA never stores the session token in JavaScript.
- Every API error is shown from the problem-details `detail`/`code`, never a blank screen.
- Money is displayed from the API's string amounts; the UI never does floating-point math on money.
- Amounts that are negative are shown in parentheses, accounting style.

## Acceptance criteria
1. Unauthenticated users are redirected to `/login`; after login with MFA pending, only the MFA screen is reachable.
2. First-time login shows the enrollment secret and `otpauth://` URI and then the 10 recovery codes, which must be confirmed as saved before continuing.
3. Review queue shows the suggested category pre-selected when the API suggests one; categorizing removes the row and shows the created journal entry's date/amount in a confirmation line.
4. Uploading a statement shows imported/duplicate counts; a parse error shows the `Line N:` message.
5. Reports render the API values verbatim (no re-computation) and the tax-line CSV link downloads through the same session.
6. All screens work at 360 px width (phone) and keyboard-only.
7. Vitest component tests cover: login → MFA flow, review-queue categorize, dashboard readiness, and error rendering.

## Out of scope
Journal entry editor UI, reconciliation UI, offline support, i18n, dark mode.
