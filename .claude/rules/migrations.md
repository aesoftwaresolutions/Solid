---
paths:
  - "backend/src/main/resources/db/migration/**"
---
# Database migration rules
- Flyway naming: `V<yyyymmddHHmm>__<short_description>.sql`.
- Append-only: never modify a migration already on main.
- One schema per module (iam, org, gl, bank, ar_ap, fa, pf, tax, stx, efile, doc, ai, audit, sys).
- Money columns: `bigint` minor units + `char(3)` currency. IDs: `uuid`.
- Add constraints in the DB (not null, check, fk, unique) — don't rely only on Java validation.
- Include RLS policy for any table with `org_id`.
