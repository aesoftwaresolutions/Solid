# 025 — People and activity

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Two things an owner needs and cannot do on screen today: see who has access to the organization (and give a
bookkeeper or accountant their own login), and see what has been done in the books.

## Scope
Frontend only — `GET/POST /orgs/{orgId}/members` and `GET /orgs/{orgId}/audit-events` already exist and are
restricted to owners and admins. A new Organization page holds both, reached from the entity list.

## Rules
- The page is offered to everyone, but the server decides: a 403 is shown as "only owners and admins can see this"
  rather than as a broken page.
- Roles are explained in plain words next to the picker, because "accountant" and "bookkeeper" are not obvious.
- Only an owner may add an owner; the page shows the server's message when it refuses rather than hiding the option
  and guessing at the caller's role.
- Audit rows show when, who, what and which object, with details rendered as compact text. Nothing in an audit row
  is ever re-interpreted or reformatted into something that looks like a claim the system did not make.
- The audit list is read-only, in reverse order, with a row count the person can raise.

## Acceptance criteria
1. The organization page lists members with their email, display name and role.
2. Adding a member sends the email and chosen role and refreshes the list.
3. A 403 when adding (for example a non-owner adding an owner) shows the server's message.
4. The activity list shows each event's time, action, actor and object, newest first.
5. Changing the row limit re-requests with the new limit.
6. A 403 on either panel shows an explanation instead of an empty page, and the rest of the page still works.

## Out of scope
Removing or changing a member's role (no endpoint yet), invitations by email, filtering or searching the audit log,
verifying the audit hash chain on screen, and anything instance-wide (that is the admin area, a later slice).
