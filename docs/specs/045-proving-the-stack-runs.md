# 045 — Proving the packaged stack runs

**Status:** Done · **Owner review:** Needed · **CPA review:** Not needed

## Goal
Every slice so far was proved by tests inside the build. Nobody had ever started the packaged jar against a
real PostgreSQL behind the real reverse proxy and used it through HTTP. Until that happens, "it works" means
"the tests pass", which is not the same claim.

## Scope
- `ops/smoke-test.py` — an outside-in check of a running stack: sign up, turn on MFA, create an organization
  and entity, apply a chart of accounts, post one balanced entry, and verify the reports agree with it.
- A short section in `docs/operations.md` telling an operator to run it after every upgrade.
- The books' integrity check (`GET .../journal/verify`, which has existed since slice 005 but was never shown)
  on the entity settings screen.

## What was actually run
The packaged jar (`solid-0.1.0-SNAPSHOT.jar`) in `eclipse-temurin:21-jre`, PostgreSQL 16 in its own container,
and the built frontend served by `caddy:2-alpine` with the project's own `Caddyfile`, on one Docker network,
reached only through the web container's port. Results:

- Flyway migrated a fresh database to `202609190001` and the app started in 9 seconds.
- `/api/v1/system/info` answered through the proxy; the security headers from the Caddyfile were present.
- The smoke test passed: net income 1500.00, balance sheet balanced, closing cash 1500.00, hash chain valid,
  and the setup list reported the first entry as done.

**Known limitation:** `docker compose build` itself still cannot run in this development sandbox — Maven
inside the build container cannot verify the sandbox proxy's TLS certificate, so dependency download fails
there. The runtime images, the compose wiring and the application were verified as above with the artifacts
built outside the container. On an ordinary machine with normal network access, `docker compose up --build`
does the whole thing; that path remains unverified here and is called out in `docs/operations.md`.

## Signing in
A fresh instance lets the first account sign itself up, so the script does that. Once an instance has a user,
sign-up closes — correctly — and the script says so and asks for `SOLID_SMOKE_EMAIL`,
`SOLID_SMOKE_PASSWORD` and `SOLID_SMOKE_TOTP_SECRET` instead. A TOTP code cannot be used twice, so if the one
it generates has already been spent it waits for the next 30-second window rather than failing over something
that fixes itself. Both paths were run against the live stack.

## Rules
- The smoke test only uses the public HTTP API. It never touches the database, so it cannot pass by accident.
- It creates its own throwaway user and organization, and asserts figures it computed itself. It leaves that
  data behind on purpose: an operator can look at it, and deleting it would need privileges this script
  should not have.
- It is a script, not a test in the build: it needs a running stack, and a build that requires one is a build
  that fails for the wrong reasons.

## Acceptance criteria
1. `python3 ops/smoke-test.py <url>` exits 0 against a healthy stack and prints each check.
2. It exits non-zero with the failing call's status and body when anything is wrong.
3. `docs/operations.md` tells the operator to run it after an upgrade, and what a failure means.
4. The entity settings screen shows whether the ledger's hash chain verifies, and how many entries it covers.

## Out of scope
Running the smoke test in CI (it needs a stack), load testing, and TLS — the production Caddyfile with a real
domain belongs to the installer slice.
