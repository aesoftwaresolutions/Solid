# 048 — Changing a password, and getting back in

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
There was no way to change a password, and no way to recover from a forgotten one. On a hosted product the
answer is "click the email we sent"; on a server someone runs themselves there may be no email at all. This
slice gives three honest answers instead: change it yourself, have an administrator hand you a one-time link,
or — when the last administrator is the one locked out — issue that link from the machine's own command line.

## Scope
`iam` module: a change-password endpoint, a one-time reset token (new `iam.password_reset` table), the
administrator and command-line ways to issue one, and the two screens.

## How it works
1. **Change it yourself** — signed in and past MFA: `POST /auth/change-password` with the current password and
   the new one. Every other session of that user is revoked; the one being used stays, so nobody is thrown out
   of the screen they are on.
2. **An administrator issues a link** — an instance administrator calls
   `POST /instance/users/{userId}/password-reset`. The response carries a token **once**; only its SHA-256 is
   stored. It is good for one hour and one use.
3. **Nobody can sign in at all** — whoever runs the server starts the jar with
   `--solid.reset-password=someone@example.com`. It prints a reset token to the console and exits without
   serving anything, so the door only opens for someone who already has the machine.
4. **Using the link**: `POST /auth/reset-password` with the token and a new password. All of that user's
   sessions are revoked, so a thief holding an old session loses it too.

## Rules
- The same password rules apply everywhere a password is set; changing to the current password is refused.
- Reset tokens are stored hashed, expire in an hour, work once, and are invalidated when a newer one is
  issued for that user.
- A reset does **not** turn off MFA and does not grant a session: the person still signs in and still passes
  their second factor. Losing the phone is what recovery codes are for.
- Resetting is audited (`password_reset_issued`, `password_reset_used`), and so is a self-service change
  (`password_changed`). The audit records who issued it, never the token.
- A wrong current password is refused with the same 401 a bad login gets, and the failure is audited.
- Nothing in this slice reveals whether an email address has an account: issuing a reset is administrator-only.

## Data contract
- `POST /api/v1/auth/change-password` `{currentPassword, newPassword}` → 204.
- `POST /api/v1/instance/users/{userId}/password-reset` → `{userId, email, expiresAt, token, resetPath}`.
- `GET /api/v1/instance/users` → `[{id, email, displayName, mfaEnabled, isInstanceAdmin}]`, so an
  administrator can find the person to reset (instance administrators only).
- `POST /api/v1/auth/reset-password` `{token, newPassword}` → 204. Unauthenticated, like login.

## Acceptance criteria
1. A signed-in user changes their password, can sign in with the new one, and cannot with the old one.
2. Changing it revokes that user's other sessions and keeps the current one working.
3. A wrong current password is refused and nothing changes.
4. An instance administrator issues a reset; the token works once, sets the new password, and then fails.
5. An expired token fails, and so does one superseded by a newer reset for the same user.
6. A non-administrator cannot issue a reset or list users.
7. A reset does not enable a session or disable MFA: the user still has to sign in and pass MFA.
8. `--solid.reset-password=<email>` prints a token and does not start the web server.
9. The audit trail shows the issue and the use, and never the token itself.

## Out of scope
Sending email, self-service "forgot password" without an administrator, password expiry policies, and
resetting someone's MFA (recovery codes already cover a lost phone).
