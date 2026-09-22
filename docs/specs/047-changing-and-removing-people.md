# 047 — Changing and removing people

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Access could be granted and never taken away. A bookkeeper who leaves, a contractor whose work is done, an
accountant given too much by mistake — none of them could be changed or removed without editing the database
by hand. That is a security hole, not a missing convenience.

## Scope
`iam` module: change a member's role, remove a member, and the screen for both.

## Rules
- Only owners and admins can change or remove anyone — the same rule that governs adding.
- Only an owner may grant or take away the `owner` role, and **only an owner may change or remove another
  owner**: an admin cannot demote the person who can demote them.
- An organization must always keep at least one owner. The last owner cannot be demoted or removed, and the
  refusal says why rather than reporting a generic error.
- Removing yourself is allowed unless you are the last owner. The response says the membership is gone, so
  the screen can send you back to the organization list instead of leaving you on a page you can no longer
  read.
- Access ends immediately: membership is checked on every request, so a removed member's next call gets a
  404 for that organization even though their session is still valid elsewhere.
- Removing a member leaves their history alone. Entries they posted, documents they uploaded and audit events
  naming them all stay: the books are a record of what happened, not of who currently has a login.
- Both actions are audited (`member_role_changed`, `member_removed`) with the old and new role.

## Data contract
- `PATCH /api/v1/orgs/{orgId}/members/{userId}` `{role}` → the updated member.
- `DELETE /api/v1/orgs/{orgId}/members/{userId}` → 204.

Refusals use 409 with a code: `LAST_OWNER` when it would leave the organization ownerless, and 403
`Only owners can …` for the owner rules above.

## Acceptance criteria
1. An owner changes a bookkeeper to an accountant; the list shows the new role and the audit trail records
   the change with both roles.
2. An owner removes a member; that person's next request for this organization is a 404, and their other
   organizations still work.
3. Demoting or removing the last owner is refused with `LAST_OWNER`, and the owner is still there afterwards.
4. An admin cannot change or remove an owner, and cannot grant the owner role.
5. A viewer cannot change or remove anyone.
6. Removing yourself works when someone else still owns the organization, and the response says so.
7. Another organization's members cannot be touched (404).
8. The people screen offers a role selector and a remove button, explains the last-owner rule, and shows the
   server's refusal rather than a generic failure.

## Out of scope
Suspending an account instance-wide, transferring ownership of an organization to another instance, and
deleting a user account altogether (their history references them, so that is a separate, careful slice).
