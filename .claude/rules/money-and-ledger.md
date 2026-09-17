---
paths:
  - "backend/src/**/ledger/**"
  - "backend/src/**/money/**"
  - "backend/src/**/billing/**"
  - "backend/src/**/banking/**"
  - "backend/src/**/reporting/**"
---
# Money & ledger rules
- All amounts use `Money`; arithmetic via its methods. Allocation/splitting must distribute remainder cents deterministically (largest remainder) and sum exactly to the original.
- Rounding mode must be explicit (HALF_UP unless a spec says otherwise); never rely on defaults.
- A posted entry's lines must sum to zero in base currency; enforce in DB (deferred constraint trigger) and in the domain model.
- Never UPDATE/DELETE posted journal rows. Reversal = new entry with `reverses_entry_id`.
- Reject entry dates on or before the entity's period lock.
- Every report query must have a golden-file test from a hand-verified fixture ledger.
- Every new table carries `org_id` and has an RLS policy + a cross-org access test.
