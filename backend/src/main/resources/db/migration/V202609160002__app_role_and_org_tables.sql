-- Runtime role used by the application. It is NOT a superuser and has no BYPASSRLS,
-- so row-level security always applies to it. The app switches to it with SET ROLE.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'solid_app') then
    create role solid_app nologin;
  end if;
end
$$;

-- Let the login user switch to the app role.
grant solid_app to current_user;

-- Grant table access in every module schema, now and for tables created later by this owner.
do $$
declare
  s text;
begin
  foreach s in array array['iam','org','gl','bank','ar_ap','fa','pf','tax','stx','efile','doc','ai','audit','sys'] loop
    execute format('grant usage on schema %I to solid_app', s);
    execute format('grant select, insert, update, delete on all tables in schema %I to solid_app', s);
    execute format('alter default privileges in schema %I grant select, insert, update, delete on tables to solid_app', s);
    execute format('alter default privileges in schema %I grant usage, select on sequences to solid_app', s);
  end loop;
end
$$;

-- Helper: current organization from the transaction setting (null when not set).
create or replace function sys.current_org_id() returns uuid
  language sql stable
  as $$ select nullif(current_setting('app.org_id', true), '')::uuid $$;
grant execute on function sys.current_org_id() to solid_app;

create table org.organization (
  id         uuid primary key,
  name       text not null check (length(trim(name)) between 1 and 200),
  kind       text not null check (kind in ('household', 'business', 'firm_client')),
  created_at timestamptz not null default now()
);

create table org.entity (
  id                uuid primary key,
  org_id            uuid not null references org.organization (id),
  kind              text not null check (kind in ('individual', 'sole_prop', 'smllc', 'partnership', 's_corp', 'c_corp', 'trust')),
  legal_name        text not null check (length(trim(legal_name)) between 1 and 200),
  fiscal_year_end   smallint not null default 12 check (fiscal_year_end between 1 and 12),
  accounting_method text not null default 'cash' check (accounting_method in ('cash', 'accrual')),
  home_state        char(2) check (home_state ~ '^[A-Z]{2}$'),
  base_currency     char(3) not null default 'USD' check (base_currency ~ '^[A-Z]{3}$'),
  created_at        timestamptz not null default now(),
  unique (org_id, id)
);

create table org.ownership (
  id              uuid primary key,
  org_id          uuid not null,
  owner_entity_id uuid not null,
  owned_entity_id uuid not null,
  percent         numeric(7, 4) not null check (percent > 0 and percent <= 100),
  effective_from  date not null,
  effective_to    date,
  created_at      timestamptz not null default now(),
  check (owner_entity_id <> owned_entity_id),
  check (effective_to is null or effective_to > effective_from),
  -- composite FKs guarantee both entities belong to the same organization as the ownership row
  foreign key (org_id, owner_entity_id) references org.entity (org_id, id),
  foreign key (org_id, owned_entity_id) references org.entity (org_id, id)
);
create index ownership_owned_idx on org.ownership (owned_entity_id);

alter table org.entity enable row level security;
alter table org.entity force row level security;
create policy entity_org_isolation on org.entity
  using (org_id = sys.current_org_id())
  with check (org_id = sys.current_org_id());

alter table org.ownership enable row level security;
alter table org.ownership force row level security;
create policy ownership_org_isolation on org.ownership
  using (org_id = sys.current_org_id())
  with check (org_id = sys.current_org_id());
