create table bank.bank_account (
  id            uuid primary key,
  org_id        uuid not null,
  entity_id     uuid not null,
  gl_account_id uuid not null,
  name          text not null check (length(trim(name)) between 1 and 120),
  institution   text check (institution is null or length(institution) <= 120),
  mask          text check (mask is null or mask ~ '^[0-9]{2,4}$'),
  created_at    timestamptz not null default now(),
  unique (org_id, id),
  unique (gl_account_id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, gl_account_id) references gl.account (org_id, id)
);

create table bank.import_batch (
  id              uuid primary key,
  org_id          uuid not null,
  bank_account_id uuid not null,
  filename        text,
  format          text not null check (format in ('csv', 'ofx')),
  parsed_count    int not null,
  imported_count  int not null,
  duplicate_count int not null,
  created_by      uuid,
  created_at      timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, bank_account_id) references bank.bank_account (org_id, id)
);

create table bank.bank_txn (
  id                    uuid primary key,
  org_id                uuid not null,
  bank_account_id       uuid not null,
  import_batch_id       uuid not null,
  external_id           text not null check (length(external_id) between 1 and 255),
  posted_date           date not null,
  amount_minor          bigint not null check (amount_minor <> 0),
  currency              char(3) not null,
  description           text not null,
  normalized_description text not null,
  status                text not null default 'new' check (status in ('new', 'categorized', 'excluded')),
  suggested_account_id  uuid,
  suggestion_source     text check (suggestion_source in ('rule', 'history')),
  category_account_id   uuid,
  journal_entry_id      uuid,
  created_at            timestamptz not null default now(),
  unique (bank_account_id, external_id),
  unique (org_id, id),
  foreign key (org_id, bank_account_id) references bank.bank_account (org_id, id),
  foreign key (org_id, import_batch_id) references bank.import_batch (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id),
  foreign key (org_id, category_account_id) references gl.account (org_id, id),
  check ((status = 'categorized') = (journal_entry_id is not null and category_account_id is not null))
);
create index bank_txn_account_status on bank.bank_txn (bank_account_id, status, posted_date);
create index bank_txn_normalized on bank.bank_txn (normalized_description) where status = 'categorized';

create table bank.categorization_rule (
  id         uuid primary key,
  org_id     uuid not null,
  entity_id  uuid not null,
  contains   text not null check (length(trim(contains)) between 2 and 100),
  account_id uuid not null,
  priority   int not null default 100 check (priority between 0 and 1000),
  created_at timestamptz not null default now(),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, account_id) references gl.account (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['bank_account', 'import_batch', 'bank_txn', 'categorization_rule'] loop
    execute format('alter table bank.%I enable row level security', t);
    execute format('alter table bank.%I force row level security', t);
    execute format('create policy %I on bank.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
