-- Templates for entries that repeat (rent, subscriptions, loan payments) and the record of what has been posted.
create table gl.recurring_entry (
  id           uuid primary key,
  org_id       uuid not null,
  entity_id    uuid not null,
  name         text not null check (length(trim(name)) between 1 and 120),
  memo         text check (memo is null or length(memo) <= 500),
  frequency    text not null check (frequency in ('monthly', 'quarterly', 'annual')),
  start_date   date not null,
  end_date     date,
  day_of_month smallint not null check (day_of_month between 1 and 31),
  is_active    boolean not null default true,
  created_at   timestamptz not null default now(),
  unique (org_id, id),
  check (end_date is null or end_date >= start_date),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);
create index recurring_entry_entity on gl.recurring_entry (entity_id, is_active);

create table gl.recurring_line (
  id           uuid primary key,
  org_id       uuid not null,
  recurring_id uuid not null,
  line_no      integer not null check (line_no > 0),
  account_id   uuid not null,
  amount_minor bigint not null,
  currency     char(3) not null,
  memo         text check (memo is null or length(memo) <= 500),
  unique (recurring_id, line_no),
  foreign key (org_id, recurring_id) references gl.recurring_entry (org_id, id) on delete cascade,
  foreign key (org_id, account_id) references gl.account (org_id, id)
);

-- One row per occurrence actually posted; the primary key is what makes re-running a range harmless.
create table gl.recurring_occurrence (
  recurring_id     uuid not null,
  occurrence_date  date not null,
  journal_entry_id uuid not null,
  org_id           uuid not null,
  created_at       timestamptz not null default now(),
  primary key (recurring_id, occurrence_date),
  foreign key (org_id, recurring_id) references gl.recurring_entry (org_id, id) on delete cascade,
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['recurring_entry', 'recurring_line', 'recurring_occurrence'] loop
    execute format('alter table gl.%I enable row level security', t);
    execute format('alter table gl.%I force row level security', t);
    execute format('create policy %I on gl.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
