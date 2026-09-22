# 050 — Fixes from the third review

**Status:** Done · **Owner review:** Needed · **Security review:** Done (this slice is the result of one)

## Goal
A hostile review of slices 044–049 — the identity and tax-figure work, the most security-sensitive stretch
of the project — found ten real defects. This slice fixes them, with a regression test for each one that can
be tested.

What the review found sound, and which therefore stays as it is: invitation tokens (32 random bytes, hashed,
looked up by hash, one use, expiry, `FOR UPDATE` on acceptance); accepting an invitation for an address that
already has an account (no password change, no session, no escalation); every new endpoint's authorization,
including the four actor/target/role combinations of the owner rules; reset semantics (no session, no MFA
change, all sessions revoked, single use, newer issue retires the older, password validated before the token
is spent); that MFA cannot be bypassed by any new path; and the tax figures' precedence, exact decimal
handling and supersede-only history.

## The fixes

1. **The "always at least one owner" rule was a check-then-write race.** Two owners removing each other at
   the same moment each counted one *other* owner and both succeeded, leaving books nobody could administer
   and no API call could repair. Both `changeRole` and `removeMember` now run in one transaction behind
   `pg_advisory_xact_lock` on that organization's membership. *(1)*
2. **The home-office rate was applied to every year**, including 2011 — before the simplified method existed
   — presenting an invented figure with a real citation attached. The rate now honours the file's
   `appliesFromTaxYear`, and a year with no published rate reports the declaration with no deduction and says
   why, the way mileage already did. *(2)*
3. **Entity settings were writable by every non-viewer**, so a bookkeeper could switch cash to accrual and
   silently change what every past report means. Owners and admins only. *(3)*
4. **That change's audit event recorded only the new values** and never the name. It now records what changed,
   from what to what. *(4)*
5. **The smoke test wrote permanent data into whatever instance it was pointed at.** It now reverses its own
   entry (the only honest way to undo a posted one), names what it creates after the run, says plainly that
   the organization stays, and offers `SOLID_SMOKE_READ_ONLY=1`. `docs/operations.md` says the same. *(5)*
6. **Invitation and reset tokens sat in the address bar** — and so in history, logs and any `Referer`. Both
   pages now strip the token from the URL as soon as they have read it. *(6)*
7. **Concurrent writes that the unique indexes caught surfaced as 500s.** A simultaneous second invitation or
   tax figure now gets the 409 the sequential case gets. *(7)*
8. **Changing a password was three separate autocommits**, so a crash could leave the new password in place
   with the old sessions still live — exactly the state someone changing a password after a break-in must not
   end in. It is one transaction. *(8)*
9. **The membership grant from an accepted invitation was recorded as a system action**, because acceptance
   is unauthenticated: the event that gives an outsider access had no actor. The accepting user and their IP
   are now passed explicitly. *(9)*
10. **Two hard casts to `java.sql.Timestamp`** would have broken both recovery paths together if the driver's
    mapping ever changed. They go through one helper that accepts what a driver may return. *(10)*

## Acceptance criteria
Each fix has a test that fails against the previous code: `ReviewFixTests` (1, 3, 4, 9),
`HomeOfficeYearTests` (2), and the existing invitation, password and tax-figure suites still pass unchanged
(7, 8, 10). The smoke-test and URL changes (5, 6) are covered by the script's own run and the page tests.

## Not fixed here, and why
Nothing from the review is outstanding. The reviewer's one suspected finding (10) was fixed anyway: the cost
was a ten-line helper, and the failure mode was both password recovery paths breaking at once.
