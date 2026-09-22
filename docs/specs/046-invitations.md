# 046 — Inviting the second person

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Sign-up closes as soon as the first account exists, which is right for a server on someone's own box — and
leaves a hole: `POST /members` needs a user who already exists, and nothing creates one. Today the only way to
add a colleague or an accountant is to reopen sign-up for the whole instance. This slice closes that hole.

## Scope
`iam` module. A new `iam.invitation` table, three endpoints for the inviter and one for the invitee, and the
screens for both. **No email is sent**: a self-hosted instance has no SMTP it can assume, and pretending
otherwise would lose invitations silently. The inviter copies a link and sends it however they already talk to
that person.

## How it works
1. An owner or admin invites an email address with a role. The server generates a 32-byte random token, stores
   only its SHA-256, and returns the token **once**, in that response. It is never readable again.
2. The invitee opens `/accept-invitation?token=…`, chooses a display name and a password, and gets an account
   plus membership of that organization. Accepting is the one way past closed sign-up, and only for the
   address that was invited.
3. If an account with that email already exists, accepting does not touch the password: the person signs in
   normally and the invitation adds the membership on their next accept, which the screen explains.
4. Invitations expire after 7 days, can be revoked, and can be accepted once.

## Rules
- The token is stored hashed. A stolen database gives no usable invitation.
- The token is compared by looking up its hash, so no secret is compared byte by byte in application code.
- The invited email is the only one the token can create: a token cannot be redirected at another address.
- Only an owner or admin may invite; only an owner may invite an owner — the same rule `POST /members` uses.
- An invitation to someone who is already a member is refused at creation with a clear message.
- Accepting is rate-limited by the same login throttle, and audited (`invitation_created`,
  `invitation_accepted`, `invitation_revoked`), because invitations are how an outsider becomes an insider.
- Expired and accepted invitations stay in the table so the audit trail has something to point at. They are
  listed with their status rather than hidden.
- A new account created by accepting still has to enrol MFA before it can do anything: the invitation grants
  membership, not an exemption.

## Data contract
- `POST /api/v1/orgs/{orgId}/invitations` `{email, role}` → `{id, email, role, expiresAt, token, acceptPath}`
  — `token` appears in this one response and nowhere else.
- `GET /api/v1/orgs/{orgId}/invitations` → `[{id, email, role, status, invitedBy, createdAt, expiresAt}]`
  where `status` is `pending`, `accepted`, `revoked` or `expired`.
- `DELETE /api/v1/orgs/{orgId}/invitations/{id}` → 204.
- `POST /api/v1/auth/accept-invitation` `{token, displayName, password}` → `{organizationId, email, created}`,
  where `created` says whether a new account was made. Unauthenticated, like signup and login.

## Acceptance criteria
1. On an instance with sign-up closed, an invited person can create their account and lands as a member with
   the role they were given.
2. The token is returned once at creation and never appears in the list endpoint or the database in plain form.
3. A second accept with the same token fails, and so does a revoked or expired one.
4. An invitation for an email that already has an account adds the membership without changing the password.
5. A viewer cannot invite; a non-owner admin cannot invite an owner.
6. Another organization's invitations are invisible (404 on the org, nothing in the list).
7. `invitation_created`, `invitation_accepted` and `invitation_revoked` appear in the audit trail.
8. The organization screen shows pending invitations with the link to copy, and lets an owner revoke one.

## Out of scope
Sending email, single sign-on, invitations to the instance rather than to an organization, and changing
someone's role after they join (that is `PATCH /members`, a later slice).
