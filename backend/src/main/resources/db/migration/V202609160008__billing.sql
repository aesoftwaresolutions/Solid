create table ar_ap.customer (
  id              uuid primary key,
  org_id          uuid not null,
  entity_id       uuid not null,
  name            text not null check (length(trim(name)) between 1 and 200),
  email           text check (email is null or length(email) <= 254),
  phone           text check (phone is null or length(phone) <= 40),
  billing_address text check (billing_address is null or length(billing_address) <= 500),
  notes           text check (notes is null or length(notes) <= 1000),
  is_archived     boolean not null default false,
  created_at      timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, name),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);

create table ar_ap.invoice (
  id               uuid primary key,
  org_id           uuid not null,
  entity_id        uuid not null,
  customer_id      uuid not null,
  invoice_number   text not null check (length(trim(invoice_number)) between 1 and 40),
  issue_date       date not null,
  due_date         date not null,
  terms            text not null check (terms in ('due_on_receipt', 'net_15', 'net_30', 'net_60')),
  memo             text check (memo is null or length(memo) <= 500),
  total_minor      bigint not null check (total_minor >= 0),
  currency         char(3) not null,
  status           text not null check (status in ('draft', 'open', 'partially_paid', 'paid', 'void')),
  journal_entry_id uuid,
  created_at       timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, invoice_number),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, customer_id) references ar_ap.customer (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id),
  check (due_date >= issue_date),
  check ((status = 'draft') = (journal_entry_id is null))
);
create index invoice_entity_status on ar_ap.invoice (entity_id, status, due_date);

create table ar_ap.invoice_line (
  id                uuid primary key,
  org_id            uuid not null,
  invoice_id        uuid not null,
  line_no           smallint not null check (line_no >= 1),
  description       text not null check (length(trim(description)) between 1 and 300),
  quantity          numeric(12, 4) not null check (quantity > 0),
  unit_price_minor  bigint not null check (unit_price_minor >= 0),
  amount_minor      bigint not null check (amount_minor >= 0),
  income_account_id uuid not null,
  unique (invoice_id, line_no),
  foreign key (org_id, invoice_id) references ar_ap.invoice (org_id, id) on delete cascade,
  foreign key (org_id, income_account_id) references gl.account (org_id, id)
);

create table ar_ap.payment (
  id                 uuid primary key,
  org_id             uuid not null,
  entity_id          uuid not null,
  customer_id        uuid not null,
  received_date      date not null,
  amount_minor       bigint not null check (amount_minor > 0),
  currency           char(3) not null,
  deposit_account_id uuid not null,
  method             text check (method is null or length(method) <= 40),
  reference          text check (reference is null or length(reference) <= 100),
  journal_entry_id   uuid not null,
  created_at         timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, customer_id) references ar_ap.customer (org_id, id),
  foreign key (org_id, deposit_account_id) references gl.account (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id)
);

create table ar_ap.payment_application (
  id           uuid primary key,
  org_id       uuid not null,
  payment_id   uuid not null,
  invoice_id   uuid not null,
  amount_minor bigint not null check (amount_minor > 0),
  unique (payment_id, invoice_id),
  foreign key (org_id, payment_id) references ar_ap.payment (org_id, id) on delete cascade,
  foreign key (org_id, invoice_id) references ar_ap.invoice (org_id, id)
);
create index payment_application_invoice on ar_ap.payment_application (invoice_id);

do $$
declare t text;
begin
  foreach t in array array['customer', 'invoice', 'invoice_line', 'payment', 'payment_application'] loop
    execute format('alter table ar_ap.%I enable row level security', t);
    execute format('alter table ar_ap.%I force row level security', t);
    execute format('create policy %I on ar_ap.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
