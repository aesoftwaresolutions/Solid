create table bank.reconciliation (
  id                     uuid primary key,
  org_id                 uuid not null,
  bank_account_id        uuid not null,
  statement_date         date not null,
  statement_ending_minor bigint not null,
  beginning_minor        bigint not null,
  currency               char(3) not null,
  status                 text not null check (status in ('in_progress', 'completed')),
  completed_at           timestamptz,
  completed_by           uuid,
  created_at             timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, bank_account_id) references bank.bank_account (org_id, id),
  check ((status = 'completed') = (completed_at is not null))
);
-- At most one reconciliation in progress per bank account.
create unique index reconciliation_one_open on bank.reconciliation (bank_account_id) where status = 'in_progress';
create index reconciliation_account_date on bank.reconciliation (bank_account_id, statement_date);

create table bank.reconciliation_line (
  reconciliation_id uuid not null,
  journal_line_id   uuid not null,
  org_id            uuid not null,
  cleared_at        timestamptz not null default now(),
  primary key (reconciliation_id, journal_line_id),
  -- a ledger line can only be cleared by one reconciliation
  unique (journal_line_id),
  foreign key (org_id, reconciliation_id) references bank.reconciliation (org_id, id) on delete cascade,
  foreign key (journal_line_id) references gl.journal_line (id)
);

do $$
declare t text;
begin
  foreach t in array array['reconciliation', 'reconciliation_line'] loop
    execute format('alter table bank.%I enable row level security', t);
    execute format('alter table bank.%I force row level security', t);
    execute format('create policy %I on bank.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
