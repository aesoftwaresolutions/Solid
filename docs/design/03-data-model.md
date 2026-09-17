# 03 — Data Model (core)

PostgreSQL. One schema per module. All tables have `id uuid` (UUIDv7 for index locality), `created_at`, `updated_at`, `created_by`, and `org_id` for row-level security.

## 1. Entity relationship overview

```
organization 1─* entity *─* ownership (owner_entity → owned_entity, pct, dates)
entity 1─* account (chart of accounts) *─1 tax_line (per form, per tax year)
entity 1─* journal_entry 1─* journal_line *─1 account
bank_connection 1─* bank_account 1─* bank_txn ─0..1 journal_entry
entity 1─* tax_return (tax_year, form_type) 1─* fact_value
tax_return 1─* form_instance 1─* form_field_value
tax_return 1─* efile_submission 1─* efile_ack
```

## 2. Organizations & entities

```sql
create table org.organization (
  id uuid primary key,
  name text not null,
  kind text not null check (kind in ('household','business','firm_client')),
  created_at timestamptz not null default now()
);

create table org.entity (
  id uuid primary key,
  org_id uuid not null references org.organization(id),
  kind text not null check (kind in ('individual','sole_prop','smllc','partnership','s_corp','c_corp','trust')),
  legal_name text not null,
  tin_encrypted bytea,             -- SSN/EIN, envelope-encrypted (see 06-security)
  tin_last4 char(4),
  fiscal_year_end smallint not null default 12,
  accounting_method text not null default 'cash' check (accounting_method in ('cash','accrual')),
  home_state char(2),
  base_currency char(3) not null default 'USD'
);

create table org.ownership (
  id uuid primary key,
  owner_entity_id uuid not null references org.entity(id),
  owned_entity_id uuid not null references org.entity(id),
  percent numeric(7,4) not null check (percent > 0 and percent <= 100),
  effective_from date not null,
  effective_to date
);
```

## 3. General ledger

Money is stored as **`bigint` minor units** (cents) plus currency code. Debit positive / credit negative in a single amount column makes the balancing constraint trivial.

```sql
create table gl.account (
  id uuid primary key,
  entity_id uuid not null references org.entity(id),
  code text not null,                    -- e.g. '6100'
  name text not null,
  type text not null check (type in ('asset','liability','equity','income','expense')),
  subtype text,                          -- bank, credit_card, ar, ap, cogs, ...
  parent_id uuid references gl.account(id),
  default_tax_line_code text,            -- e.g. 'F1040.SCH_C.L8'
  is_archived boolean not null default false,
  unique (entity_id, code)
);

create table gl.period_lock (
  entity_id uuid primary key references org.entity(id),
  locked_through date not null
);

create table gl.journal_entry (
  id uuid primary key,
  entity_id uuid not null references org.entity(id),
  entry_date date not null,
  memo text,
  source text not null,                  -- manual, bank, invoice, bill, depreciation, closing, reversal
  source_ref uuid,                       -- id of invoice / bank_txn etc.
  status text not null check (status in ('draft','posted')),
  reverses_entry_id uuid references gl.journal_entry(id),
  posted_at timestamptz,
  hash bytea                             -- hash chain: sha256(prev_hash || canonical entry)
);

create table gl.journal_line (
  id uuid primary key,
  journal_entry_id uuid not null references gl.journal_entry(id),
  account_id uuid not null references gl.account(id),
  amount_minor bigint not null check (amount_minor <> 0),   -- +debit / -credit
  currency char(3) not null default 'USD',
  fx_rate numeric(18,8),                                   -- to entity base currency
  base_amount_minor bigint not null,
  tax_line_code text,                    -- override of account default
  customer_id uuid, vendor_id uuid, class_id uuid, project_id uuid,
  memo text
);
```

**Invariants (enforced in DB, not just app):**
- Deferred constraint trigger: for each posted `journal_entry`, `sum(base_amount_minor) = 0`.
- Posted entries & lines are immutable (trigger blocks UPDATE/DELETE); fixes = reversal entry.
- `entry_date > period_lock.locked_through` on insert.
- Row-level security on `org_id` via `current_setting('app.org_id')`.

**Balances:** `gl.account_balance_daily(account_id, date, debit_minor, credit_minor)` maintained in the same transaction as posting → fast reports without scanning all lines.

## 4. Banking

```sql
create table bank.bank_txn (
  id uuid primary key,
  bank_account_id uuid not null,
  external_id text,                      -- aggregator/OFX FITID, used for dedupe
  posted_date date not null,
  amount_minor bigint not null,
  description text,
  merchant_name text,
  status text not null check (status in ('new','suggested','categorized','excluded','reconciled')),
  suggested_account_id uuid,
  suggestion_source text,                -- rule, ai_local, ai_cloud, history
  suggestion_confidence numeric(4,3),
  journal_entry_id uuid references gl.journal_entry(id),
  unique (bank_account_id, external_id)
);
```

## 5. Tax

```sql
create table tax.rule_pack (
  id uuid primary key,
  jurisdiction text not null,            -- 'US', 'US-CA', ...
  tax_year smallint not null,
  version text not null,                 -- e.g. 2026.3.1
  signature bytea not null,
  installed_at timestamptz not null,
  unique (jurisdiction, tax_year, version)
);

create table tax.tax_return (
  id uuid primary key,
  entity_id uuid not null references org.entity(id),
  jurisdiction text not null,
  tax_year smallint not null,
  return_type text not null,             -- F1040, F1120S, F1065, CA540 ...
  rule_pack_id uuid not null references tax.rule_pack(id),
  status text not null,                  -- projection, in_progress, review, signed, filed, accepted, rejected, amended
  filing_status text
);
-- one active original return per entity/jurisdiction/year/type
create unique index tax_return_active_uq on tax.tax_return (entity_id, jurisdiction, tax_year, return_type)
  where status not in ('projection','amended');

create table tax.fact_value (
  tax_return_id uuid not null references tax.tax_return(id),
  fact_path text not null,               -- e.g. '/income/w2[0]/box1'
  value jsonb not null,
  source text not null,                  -- user, ledger, document, import, carryforward, computed
  source_ref uuid,
  updated_at timestamptz not null,
  primary key (tax_return_id, fact_path)
);

create table tax.carryforward (
  entity_id uuid not null,
  kind text not null,                    -- nol, capital_loss_st, capital_loss_lt, sec179, passive_loss, charitable
  origin_year smallint not null,
  amount_minor bigint not null,
  remaining_minor bigint not null,
  primary key (entity_id, kind, origin_year)
);
```

`tax_line` mappings live **inside rule packs** (so a line can move between years) and are referenced by stable codes like `F1040.SCH_C.L8`.

## 6. Audit

```sql
create table audit.event (
  id bigserial primary key,
  occurred_at timestamptz not null default now(),
  org_id uuid, actor_user_id uuid, actor_ip inet,
  action text not null,                  -- login, view_sensitive, create, update, post, export, efile_submit ...
  object_type text, object_id uuid,
  before jsonb, after jsonb,             -- sensitive fields redacted
  prev_hash bytea, hash bytea not null   -- tamper-evident chain
);
```
App DB role has INSERT-only on `audit.event`.

## 7. Design notes & trade-offs

| Decision | Alternative | Why |
|---|---|---|
| bigint cents | `numeric(19,4)` | Exact, fast, no float bugs; 4-decimal needs (FX, unit prices) kept in separate columns |
| Signed single amount column | debit/credit columns | Sum-to-zero constraint is one line; UI still shows Dr/Cr |
| Immutable posted entries | Editable entries | Audit trail and hash chain; matches accounting practice |
| Facts as JSONB rows | Wide table per form | Forms change every year; rules drive structure |
| UUIDv7 | bigserial | Safe to generate client-side & merge across installs (import/export) |
| RLS on org_id | App-only filtering | Defense in depth for multi-org installs |
