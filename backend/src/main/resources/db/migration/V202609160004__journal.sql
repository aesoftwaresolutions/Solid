create table gl.period_lock (
  entity_id      uuid primary key,
  org_id         uuid not null,
  locked_through date not null,
  updated_at     timestamptz not null default now(),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);

create table gl.journal_entry (
  id                uuid primary key,
  org_id            uuid not null,
  entity_id         uuid not null,
  entry_date        date not null,
  memo              text check (memo is null or length(memo) <= 500),
  source            text not null default 'manual' check (source ~ '^[a-z_]{1,30}$'),
  source_ref        uuid,
  status            text not null check (status in ('draft', 'posted')),
  reverses_entry_id uuid,
  posting_seq       bigint,
  prev_hash         text,
  hash              text,
  posted_at         timestamptz,
  idempotency_key   text check (idempotency_key is null or length(idempotency_key) between 1 and 100),
  created_at        timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, posting_seq),
  unique (entity_id, idempotency_key),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, reverses_entry_id) references gl.journal_entry (org_id, id),
  check ((status = 'posted') = (posting_seq is not null and hash is not null and posted_at is not null))
);
-- An entry can be reversed only once.
create unique index journal_entry_one_reversal on gl.journal_entry (reverses_entry_id) where reverses_entry_id is not null;
create index journal_entry_entity_date on gl.journal_entry (entity_id, entry_date);

create table gl.journal_line (
  id               uuid primary key,
  org_id           uuid not null,
  journal_entry_id uuid not null,
  line_no          smallint not null check (line_no >= 1),
  account_id       uuid not null,
  amount_minor     bigint not null check (amount_minor <> 0),
  currency         char(3) not null check (currency ~ '^[A-Z]{3}$'),
  memo             text check (memo is null or length(memo) <= 500),
  unique (journal_entry_id, line_no),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id) on delete cascade,
  foreign key (org_id, account_id) references gl.account (org_id, id)
);
create index journal_line_account on gl.journal_line (account_id);

-- ---------- Rule: posted entries and their lines are immutable ----------
create or replace function gl.forbid_posted_entry_change() returns trigger
  language plpgsql as $$
begin
  if tg_op = 'DELETE' and old.status = 'posted' then
    raise exception 'Posted journal entry % cannot be deleted; post a reversal instead', old.id
      using errcode = 'P0001';
  end if;
  if tg_op = 'UPDATE' and old.status = 'posted' then
    raise exception 'Posted journal entry % cannot be changed; post a reversal instead', old.id
      using errcode = 'P0001';
  end if;
  return coalesce(new, old);
end $$;

create trigger journal_entry_immutable
  before update or delete on gl.journal_entry
  for each row execute function gl.forbid_posted_entry_change();

create or replace function gl.forbid_posted_line_change() returns trigger
  language plpgsql as $$
declare
  entry_status text;
begin
  select status into entry_status from gl.journal_entry
   where id = coalesce(new.journal_entry_id, old.journal_entry_id);
  if entry_status = 'posted' then
    raise exception 'Lines of posted journal entry % cannot be inserted, changed or deleted',
      coalesce(new.journal_entry_id, old.journal_entry_id) using errcode = 'P0001';
  end if;
  return coalesce(new, old);
end $$;

create trigger journal_line_immutable
  before insert or update or delete on gl.journal_line
  for each row execute function gl.forbid_posted_line_change();

-- ---------- Rule: posted entries balance (checked at COMMIT) ----------
create or replace function gl.check_entry_balanced() returns trigger
  language plpgsql as $$
declare
  entry record;
  line_count int;
  total bigint;
  currencies int;
begin
  select id, status into entry from gl.journal_entry where id = new.id;
  if not found or entry.status <> 'posted' then
    return null;
  end if;
  select count(*), coalesce(sum(amount_minor), 0), count(distinct currency)
    into line_count, total, currencies
    from gl.journal_line where journal_entry_id = new.id;
  if line_count < 2 then
    raise exception 'Posted journal entry % must have at least 2 lines', new.id using errcode = 'P0001';
  end if;
  if total <> 0 or currencies <> 1 then
    raise exception 'Posted journal entry % does not balance (sum % minor units)', new.id, total
      using errcode = 'P0001';
  end if;
  return null;
end $$;

create constraint trigger journal_entry_balanced
  after insert or update on gl.journal_entry
  deferrable initially deferred
  for each row execute function gl.check_entry_balanced();

-- ---------- Rule: no posting into a locked period ----------
create or replace function gl.check_period_lock() returns trigger
  language plpgsql as $$
declare
  lock_date date;
begin
  select locked_through into lock_date from gl.period_lock where entity_id = new.entity_id;
  if lock_date is not null and new.entry_date <= lock_date then
    raise exception 'Period is locked through % for this entity', lock_date using errcode = 'P0001';
  end if;
  return new;
end $$;

create trigger journal_entry_period_lock
  before insert or update on gl.journal_entry
  for each row execute function gl.check_period_lock();

-- ---------- Row-level security ----------
alter table gl.period_lock enable row level security;
alter table gl.period_lock force row level security;
create policy period_lock_org_isolation on gl.period_lock
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());

alter table gl.journal_entry enable row level security;
alter table gl.journal_entry force row level security;
create policy journal_entry_org_isolation on gl.journal_entry
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());

alter table gl.journal_line enable row level security;
alter table gl.journal_line force row level security;
create policy journal_line_org_isolation on gl.journal_line
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());
