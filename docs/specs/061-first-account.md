# 061 — Making the first account

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
A freshly installed Solid has no accounts and no default password — deliberately, because a shipped default
credential is a shipped vulnerability. The first person to sign up becomes the instance administrator.

But the web UI has no way to do it. `LoginPage` only signs in, and nothing in the frontend calls the signup
endpoint that exists. So the first thing anyone sees after installing is a sign-in form they cannot get past,
with nothing telling them what to do. The API works; the screen is missing. This slice adds it.

Found by the owner asking, of a freshly installed copy, "what are the credentials?" — which is exactly the
question the first screen should answer without being asked.

## Scope
`iam` and the login screen. No change to how accounts, passwords or MFA actually work.

## How it behaves
- The login screen asks the server, before showing anything, whether this instance has any accounts yet.
- **No accounts:** it shows *Create the first account* — display name, email, password — and says plainly that
  this account will be the administrator, and that the password must be at least 12 characters.
- **Accounts exist:** it shows the sign-in form exactly as it does today.
- Creating the first account signs straight in and goes on to two-factor setup, which is mandatory as ever.
  One continuous path from installing to being in the books.
- A closed instance never offers the form. It is offered only when there is genuinely nobody.

## Data contract
- `GET /api/v1/auth/setup-state` → `{"setupNeeded": true|false}` — public, because it has to be readable by
  someone who has no account.

## Rules
- `setupNeeded` says nothing beyond whether the instance is unclaimed. No counts, no names, no versions.
- This discloses nothing that was not already true: an unclaimed instance already accepts the first signup,
  and that is the design. It makes the existing state visible rather than changing it.
- The server is still the authority. The form is a convenience; `signup` itself refuses a second account on a
  closed instance, and that check does not move into the browser.
- The password rule the server enforces (12–128 characters) is stated on the form, before it is typed, rather
  than as an error after.

## Acceptance criteria
1. On an instance with no accounts, `GET /api/v1/auth/setup-state` returns `setupNeeded: true` without
   authentication; after an account exists it returns `false`.
2. The login screen shows the create-first-account form when, and only when, `setupNeeded` is true.
3. Creating the first account from that form logs in and lands on two-factor setup.
4. The form says the account will be the administrator and that the password needs 12 characters.
5. A second account cannot be created this way: with accounts present the form is not offered, and the
   endpoint still refuses on a closed instance.
6. Everything already working keeps working — sign-in, two-factor, invitations are untouched.

## Out of scope
Changing the signup rules, seeding a demo organization, and an "instance is unclaimed" warning on a server
install (worth having, but it belongs with the instance admin screens).
