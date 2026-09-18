-- Monthly budgets for a household (or any entity). One budget per entity per month; one line per account.
create table pf.budget (
  id           uuid primary key,
  org_id       uuid not null,
  entity_id    uuid not null,
  period_month date not null check (extract(day from period_month) = 1),
  currency     char(3) not null,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, period_month),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);

create table pf.budget_line (
  budget_id    uuid not null,
  account_id   uuid not null,
  amount_minor bigint not null check (amount_minor >= 0),
  org_id       uuid not null,
  primary key (budget_id, account_id),
  foreign key (org_id, budget_id) references pf.budget (org_id, id) on delete cascade,
  foreign key (org_id, account_id) references gl.account (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['budget', 'budget_line'] loop
    execute format('alter table pf.%I enable row level security', t);
    execute format('alter table pf.%I force row level security', t);
    execute format('create policy %I on pf.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
