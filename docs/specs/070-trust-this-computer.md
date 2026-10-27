# 070 — Trust this computer (skip the code next time)

**Status:** In review · **Owner review:** Needed · **Security review:** Done (review of 2026-10-26, findings below fixed)

*Renumbered from 068, which the estimated-tax worksheet already uses.*

## Goal
Signing in needs the password and the authenticator code, every time. On the machine where Solid actually
runs — the desktop build, one person, one loopback port — that is a nuisance played on a loop. After the
code has been proven once on a machine, that machine may skip it for the next month. Everything that makes
the account worth protecting (the password, and the code on any new machine) is untouched.

## How it works
- `POST /auth/mfa/trusted-device`, after a code has just been proven, issues a 32-byte random token as an
  HttpOnly, SameSite=Strict cookie scoped to `/api/v1/auth`, good for 30 days. Only its SHA-256 is stored;
  one table row per handout, with the address and browser it was made on, exactly like a session row.
- `POST /auth/mfa/trusted-check` is the first thing the sign-in screen asks after a password is accepted. A
  live, unexpired row for this account promotes the pending session to verified at once — the code is skipped
  only when both this machine's cookie and tonight's password are right.
- `DELETE /auth/mfa/trusted-devices` forgets everything this account ever handed out. Expired rows are
  deleted as they are encountered; there is no scheduler to wait on.

## Rules
- **Only a typed code earns a device token.** `trusted-device` refuses a session still waiting for its code
  (401 `MFA_REQUIRED`) — otherwise the password alone could mint a token and hand it to `trusted-check` — and
  refuses a session that itself skipped the code through a device (409 `CODE_REQUIRED`), so the 30 days cannot
  renew themselves. `iam.session.mfa_via_device` records which way a session passed.
- **A changed or reset password forgets every trusted computer**, in the same transaction that revokes the
  sessions.
- The token is 256 random bits, stored hashed. A read of the table yields nothing usable.
- The shortcut applies only within one account on one machine: a cookie minted for one account does nothing
  for another (`404`, same as unknown).
- The cookie is scoped to `/api/v1/auth` so it is never sent to the books themselves.
- The session still exists and still has its idle and absolute timeouts; skipping the code does not keep
  anybody signed in for longer.

## Data contract
- `POST /api/v1/auth/mfa/trusted-device` (signed in, post-MFA) → 200 `{trustedForDays: 30}` and the cookie.
- `POST /api/v1/auth/mfa/trusted-check` (signed in, MFA pending) → 204 when the device is live for this
  account and the session is now verified; 404 `DEVICE_NOT_TRUSTED` otherwise.
- `DELETE /api/v1/auth/mfa/trusted-devices` → `{forgotten}`.

## Acceptance criteria
1. Prove the code once, trust the device, sign out, sign back in with the cookie: no code asked, session works.
2. Without the cookie the code is still required; a garbage cookie changes nothing.
3. A cookie minted for one account does nothing on another account.
4. The raw token never reaches the table (only its hash).
5. Expired rows are refused and swept on sight.
6. A session that has not proven the code cannot mint a token, and gets no cookie (`sec1`).
7. A session promoted by a device cannot mint the next token (`sec2`).
8. Changing the password leaves no trusted device for that account (`sec3`).
9. Sign-in screen: a trusted computer goes straight in; otherwise the code screen offers "Trust this computer
   for 30 days", off by default, and remembers the computer only when ticked. The account page can forget
   them all.

## Security review (2026-10-26)
| Finding | Severity | Fix |
|---|---|---|
| A session pending MFA could call `trusted-device` (the `/auth/mfa/**` paths admit it), mint a token and pass `trusted-check`: the password alone signed in | Critical | `trusted-device` requires a verified session |
| A device-promoted session could mint the next token, renewing trust forever without a code | High | `mfa_via_device`; such sessions get 409 |
| Password change and reset left trusted devices alive | High | Deleted in the same transaction |
| Migration dated 2026-09-28, before already-applied migrations: Flyway would refuse to start the deployed server | High (outage) | Re-dated `V202610260001` |

## Left for the owner
- Devices are not listed one by one on the account page yet (sessions are); only "forget all" is there.

## Out of scope
Passkeys/WebAuthn, per-device friendly names, push notification on first use, and any weakening of the
password step itself.
