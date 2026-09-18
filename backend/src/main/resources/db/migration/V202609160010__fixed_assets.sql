create table fa.asset (
  id                              uuid primary key,
  org_id                          uuid not null,
  entity_id                       uuid not null,
  name                            text not null check (length(trim(name)) between 1 and 200),
  description                     text check (description is null or length(description) <= 500),
  category                        text check (category is null or length(category) <= 60),
  placed_in_service_date          date not null,
  cost_minor                      bigint not null check (cost_minor > 0),
  salvage_minor                   bigint not null default 0 check (salvage_minor >= 0),
  useful_life_months              int not null check (useful_life_months between 1 and 600),
  currency                        char(3) not null,
  method                          text not null default 'straight_line' check (method = 'straight_line'),
  asset_account_id                uuid not null,
  accumulated_account_id          uuid not null,
  depreciation_expense_account_id uuid not null,
  status                          text not null default 'active' check (status in ('active', 'disposed')),
  disposal_date                   date,
  disposal_journal_entry_id       uuid,
  created_at                      timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, asset_account_id) references gl.account (org_id, id),
  foreign key (org_id, accumulated_account_id) references gl.account (org_id, id),
  foreign key (org_id, depreciation_expense_account_id) references gl.account (org_id, id),
  foreign key (org_id, disposal_journal_entry_id) references gl.journal_entry (org_id, id),
  check (salvage_minor < cost_minor),
  check ((status = 'disposed') = (disposal_date is not null))
);
create index asset_entity_status on fa.asset (entity_id, status);

create table fa.depreciation_entry (
  id               uuid primary key,
  org_id           uuid not null,
  asset_id         uuid not null,
  period_month     date not null, -- first day of the month
  amount_minor     bigint not null check (amount_minor > 0),
  journal_entry_id uuid not null,
  created_at       timestamptz not null default now(),
  unique (asset_id, period_month),
  foreign key (org_id, asset_id) references fa.asset (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['asset', 'depreciation_entry'] loop
    execute format('alter table fa.%I enable row level security', t);
    execute format('alter table fa.%I force row level security', t);
    execute format('create policy %I on fa.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
