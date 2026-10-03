# 068 — Trust this computer (skip the code next time)

**Status:** In progress · **Owner review:** Needed · **Security review:** Needed

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

## Left for the owner
- **A password change currently does not forget trusted devices.** Revoking sessions there already exists;
  wiring devices into it is a small follow-up and the obvious one a reviewer will ask about.
- Devices are not listed on the account page yet (sessions are). Same follow-up.

## Out of scope
Passkeys/WebAuthn, per-device friendly names, push notification on first use, and any weakening of the
password step itself.
