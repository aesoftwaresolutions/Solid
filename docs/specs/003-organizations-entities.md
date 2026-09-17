# 003 — Organizations, entities & tenant isolation

**Status:** Done · **Owner review:** Needed

## Goal
Create organizations (a household, a business, a firm's client) and the legal/tax entities inside them, with ownership links — and guarantee at the database level that one organization's data is never visible to another.

## Scope
- `common` open module: UUIDv7 id generator.
- `org` module: organization, entity, ownership; REST API.
- Platform: `OrgScope` helper that runs work inside a transaction with the PostgreSQL setting `app.org_id`; runtime DB role `solid_app` that is subject to row-level security (RLS).

## Data contracts
Tables (schema `org`): `organization(id, name, kind, created_at)`, `entity(id, org_id, kind, legal_name, fiscal_year_end, accounting_method, home_state, base_currency, created_at)`, `ownership(id, org_id, owner_entity_id, owned_entity_id, percent, effective_from, effective_to)`.

API:
- `POST /api/v1/orgs` `{name, kind}` → 201 org
- `GET /api/v1/orgs/{orgId}` → org
- `POST /api/v1/orgs/{orgId}/entities` `{kind, legalName, fiscalYearEnd?, accountingMethod?, homeState?, baseCurrency?}` → 201 entity
- `GET /api/v1/orgs/{orgId}/entities` → list
- `POST /api/v1/orgs/{orgId}/ownerships` `{ownerEntityId, ownedEntityId, percent: "60.0000", effectiveFrom, effectiveTo?}` → 201
- `GET /api/v1/orgs/{orgId}/ownerships` → list
- Errors: RFC 9457 problem details (400 validation, 404 not found, 409 business rule).

## Acceptance criteria
1. Organization kinds: `household|business|firm_client`. Entity kinds: `individual|sole_prop|smllc|partnership|s_corp|c_corp|trust`. Invalid values → 400.
2. Entity defaults: fiscalYearEnd 12, accountingMethod `cash`, baseCurrency `USD`. fiscalYearEnd must be 1–12; homeState two uppercase letters.
3. RLS: with `app.org_id` set to org B, rows of org A in `org.entity` and `org.ownership` are invisible, and inserting a row with org A's id fails. With no org set, no rows are visible.
4. The application connects through role `solid_app` (non-superuser, no BYPASSRLS); Flyway migrates with the owner connection.
5. Ownership rules: percent > 0 and ≤ 100 with up to 4 decimals; owner ≠ owned; both entities exist in the same org (else 404); for an owned entity the total percent of overlapping ownership periods must not exceed 100 (409).
6. `effectiveTo`, when given, must be after `effectiveFrom` (400).
7. Requesting another org's entity list returns only that org's data; unknown org → 404.
8. Every new table has `org_id` (except `organization`) with a forced RLS policy — enforced by an automated test that inspects `pg_class`/`pg_policies` for all tables in module schemas.

## Out of scope
Users, authentication, and org membership (slice 007 — until then the API is unauthenticated and for local development only). TIN storage/encryption (slice 007 security work).

## Decisions made without owner input
- Organization table itself has no RLS (listing orgs will be filtered by membership in slice 007).
- Ownership overlap check is done in the application inside a transaction with a row lock on the owned entity.
- UUIDv7 ids generated in Java.
