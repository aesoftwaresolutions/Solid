# 051 — Where you are signed in

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Sessions have been recorded since slice 007 — with the IP, the browser and the times — and nobody could see
them. If a laptop is lost, or a session looks wrong, the only remedies were changing the password or waiting
twelve hours. This slice shows a person their own sessions and lets them end any of them.

## Scope
`iam` module: three endpoints and a card on the account screen. Nothing new is stored; this reads and revokes
rows that already exist.

## Data contract
- `GET /api/v1/auth/sessions` → `[{id, createdAt, lastSeenAt, expiresAt, ip, userAgent, mfaVerified,
  current}]` — the caller's live sessions only, newest first. A revoked or expired session is not listed.
- `DELETE /api/v1/auth/sessions/{sessionId}` → 204. Yours only.
- `POST /api/v1/auth/sessions/revoke-others` → `{revoked}` — ends every session except this one.

## Rules
- A person sees and ends **their own** sessions and nobody else's; another user's session id is a 404, not a
  403, so the ids cannot be probed.
- Ending the session you are using is allowed and means signing out — the response is the same 204 and the
  next request is unauthorized.
- The list shows what was recorded at sign-in: an IP and a user-agent string, no geolocation and no device
  fingerprinting. Solid does not know where you were, and pretending otherwise would be theatre.
- Revoking takes effect immediately, because every request checks `revoked_at`.
- Both actions are audited (`session_revoked`, with how many).
- Sessions past their absolute expiry or idle cutoff are already dead; they are filtered out rather than
  listed as something to end.

## Acceptance criteria
1. A signed-in person sees their own current session marked `current: true`, with its IP and browser.
2. A second session for the same person appears in the list, and ending it makes that session's next request
   unauthorized while this one keeps working.
3. Another person's session id gives 404, and their sessions never appear in the list.
4. "Sign out everywhere else" revokes every other session, reports how many, and leaves this one working.
5. Ending the current session signs the caller out.
6. Expired and already-revoked sessions are not listed.
7. The account screen lists the sessions and offers both buttons.

## Out of scope
Naming devices, geolocation, push notifications about new sign-ins, and any session control an administrator
could exercise over someone else (that is an account-suspension feature, and a different question).
