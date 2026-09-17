# 001 — Project skeleton

**Status:** Done · **Owner review:** Needed (decisions below were made autonomously)

## Goal
A buildable, testable, runnable monorepo foundation so every later slice has a place to live and a check to run.

## Scope
- `backend/`: Java 21, Spring Boot 3.5, Spring Modulith, Flyway, PostgreSQL driver, Actuator; Maven wrapper.
- `frontend/`: React 19 + TypeScript + Vite + Vitest.
- `docker-compose.yml`: `postgres`, `app` (backend), `web` (Caddy serving frontend + proxy `/api` to app).
- `.github/workflows/ci.yml`: backend verify, frontend test/build, dependency license check.

## Data contracts
- `GET /api/v1/system/info` → `200 {"name":"Solid","version":"<app version>","databaseSchemaVersion":"<flyway version>"}`
- Flyway `V202609160001__create_module_schemas.sql` creates schemas: iam, org, gl, bank, ar_ap, fa, pf, tax, stx, efile, doc, ai, audit, sys.

## Acceptance criteria
1. `cd backend && ./mvnw verify` passes; tests start a real PostgreSQL 16 via Testcontainers.
2. Application context starts and Flyway applies migrations; all 14 module schemas exist.
3. `GET /api/v1/system/info` returns name `Solid`, a non-blank version, and the latest Flyway version.
4. Spring Modulith verification passes (no illegal cross-module dependencies).
5. Architecture rule: no `float`/`double` fields or method parameters anywhere in `com.aesoftwaresolutions.solid` production code.
6. `cd frontend && npm test` passes; the home page renders the product name and shows API info when the API responds, or a friendly error when it doesn't.
7. `docker compose up --build` serves the web app on http://localhost:8080 and `/api/v1/system/info` through the proxy.
8. CI workflow runs criteria 1, 5, 6 and a license check that fails on GPL/AGPL dependencies.

## Decisions made without owner input (review these)
- Base Java package `com.aesoftwaresolutions.solid`.
- Maven (not Gradle) because it's more common in beginner tutorials.
- Plain Spring JDBC for now; jOOQ introduced when the ledger needs complex queries.
- Web container is Caddy (automatic HTTPS later, simple config).

## Out of scope
Auth, any business tables, production TLS, installer script.

## End-to-end verification
```
cd backend && ./mvnw verify
cd ../frontend && npm ci && npm test && npm run build
cd .. && docker compose up --build   # then open http://localhost:8080
```
