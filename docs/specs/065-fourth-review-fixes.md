# 065 — Fixes from the fourth review

**Status:** In progress · **Owner review:** Needed · **Security review:** Done (this is its outcome)

## Goal
A full audit of slice 064 was carried out in six lanes (identity, data isolation, ledger, sales and banking,
tax/files/desktop, frontend) following `solid-audit-plan.md`. Each finding was then re-verified against the code.
This slice fixes every confirmed finding that does not need the owner's decision, each one with a regression
test that failed before the fix.

## Fixes

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | Critical | Voiding an invoice ignored credit notes written against its lines, so the sale was reversed twice | Void refuses while any non-void credit note line points at the invoice; issuing a credit note re-checks that every invoice it credits is still open |
| 2 | High | The desktop database used `trust` login on its loopback port: any account on the machine was superuser | A generated password (owner-only file beside the master key) and a `pg_hba.conf` that requires `scram-sha-256`; the app connects with that password |
| 3 | High | A full credit split across credit notes reversed a cent too little or too much tax | The note that finishes a line reverses whatever tax is left; no partial reversal may take the line's total past what was charged |
| 4 | Medium | One quote could be converted into two invoices by concurrent requests | The quote row is locked and the check, invoice and status change are one transaction; the frontend button disables while busy |
| 5 | Medium | Overlapping recurring-invoice runs could bill one occurrence twice | The template row is locked before checking for the occurrence |
| 6 | Medium | Un-categorizing a bank transaction could rewrite a month already reconciled | Refused while its journal line is cleared in any reconciliation |
| 7 | Medium | No limit on login, MFA, invitation or password-reset attempts | A per-address limit on those endpoints (`solid.auth.attempts-per-minute`, default 20) answering 429 |
| 8 | Medium | A viewer could list every member's email and role | Viewers get 403 on `GET /members`; every working role keeps it (see "Left for the owner") |
| 9 | Medium | The tax-line CSV let an account name run as a spreadsheet formula | One shared cell writer, used by both CSV outputs, that neutralises formulas |
| 10 | Medium | On Windows the master key had no permissions of its own | Its ACL is replaced with one granting only the current user |
| 11 | Low | `"1e3"` was accepted as an amount of 1,000.00 | Amounts arriving over the API must be plain decimals (the `Money` type itself still reads exponents, as spec 002's tests expect) |
| 12 | Low | A recurring invoice template accepted an account or tax rate it could never bill with | The same checks an invoice makes, at the moment the template is saved |
| 13 | Low | Two concurrent recurring-entry runs, or two reversals, failed with a vague conflict | The template (or the entry) is locked for the whole operation |
| 14 | Low | The audit log's IP address could be set by any caller through `X-Forwarded-For` | Forwarded headers are honoured only from trusted proxies (Tomcat `RemoteIpValve`) |
| 15 | Low | Missing database backstops | Credit-note lines reference invoice lines and tax rates within the same organization; `sys.instance` is insert-only for the app; a recurring line cannot be zero |
| 16 | Low | A small logo could decode to hundreds of megabytes on every PDF | Logo dimensions are read without decoding and capped at 4000×4000 |
| 17 | Low | The licence check did not block LGPL | LGPL's names added to the blocklist (see "Left for the owner": the check matches exact names only) |
| 18 | Low | Two overlapping depreciation runs aborted each other | Runs for one entity are serialised |
| 19 | Low | Frontend: quote buttons active while busy; withdrawing an invitation failed silently; a password-reset link stayed on screen | Buttons disable, the error shows, and the link can be cleared |

## Left for the owner

- **Linking a document to a record does not check the record exists** (`doc.document_link.object_id`). Fixing it
  means the documents module has to ask four other modules, which is a design choice about module boundaries.
- **lightningcss (MPL-2.0)** reaches the build through Vite. Nothing of it ships, but MPL-2.0 is not on the list in
  CLAUDE.md. Allow it for build-only tools, or replace it.
- **Reversing any entry whose lines are already reconciled.** Fix 6 covers the bank screen; reversals from the
  journal, invoice void and so on still can. The general fix is a ledger-level hook, which is a design decision.

- **Who may list members.** Spec 007 says owner/admin only; spec 047's tests have a bookkeeper listing them.
  This slice closes the list to viewers, which is the real exposure, and leaves the working roles as 047 has
  them. Say if it should be owner/admin only after all.
- **The licence rule and the licence check disagree with the build as it stands.** The check matches licence
  names exactly, so it passes "LGPL-2.1-only" and "GPL2 w/ CPE". Two Spring Boot essentials are already in:
  Logback (EPL-2.0 *or* LGPL-2.1) and Jakarta Annotations (EPL-2.0 *or* GPL-2.0 with the Classpath Exception).
  Both are usable under EPL-2.0, which is weak copyleft and not on the list in CLAUDE.md. Blocking the
  SPDX names outright fails the build on these two, because the plugin cannot express "dual-licensed, one
  option acceptable". Decide whether EPL-2.0 joins the list; the check can then be rewritten as an allow-list.

## Acceptance criteria
Each fix has a regression test that failed before the fix and passes after it, named after its row above. The
full backend suite, the frontend tests and the frontend build pass.
