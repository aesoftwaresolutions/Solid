-- Spec 057. A credit note takes money off what a customer owes without pretending the invoice never happened.
-- Issuing posts the mirror of an invoice (income debited, receivables credited); applying it to an invoice
-- posts nothing, because the ledger already moved.

create table ar_ap.credit_note (
  id               uuid primary key,
  org_id           uuid not null,
  entity_id        uuid not null,
  customer_id      uuid not null,
  credit_number    text not null check (length(trim(credit_number)) between 1 and 40),
  issue_date       date not null,
  memo             text check (memo is null or length(memo) <= 500),
  total_minor      bigint not null default 0 check (total_minor >= 0),
  currency         char(3) not null,
  status           text not null check (status in ('draft', 'issued', 'void')),
  journal_entry_id uuid,
  created_at       timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, credit_number),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, customer_id) references ar_ap.customer (org_id, id),
  -- A draft has no entry; anything that has left draft has one.
  check ((status = 'draft') = (journal_entry_id is null))
);
create index credit_note_entity_idx on ar_ap.credit_note (entity_id, issue_date desc);

create table ar_ap.credit_note_line (
  id                uuid primary key,
  org_id            uuid not null,
  credit_note_id    uuid not null,
  line_no           smallint not null check (line_no >= 1),
  description       text not null check (length(trim(description)) between 1 and 300),
  quantity          numeric(12, 4) not null check (quantity > 0),
  unit_price_minor  bigint not null check (unit_price_minor >= 0),
  amount_minor      bigint not null check (amount_minor >= 0),
  income_account_id uuid not null,
  unique (credit_note_id, line_no),
  foreign key (org_id, credit_note_id) references ar_ap.credit_note (org_id, id) on delete cascade,
  foreign key (org_id, income_account_id) references gl.account (org_id, id)
);

create table ar_ap.credit_application (
  id             uuid primary key,
  org_id         uuid not null,
  credit_note_id uuid not null,
  invoice_id     uuid not null,
  amount_minor   bigint not null check (amount_minor > 0),
  created_at     timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, credit_note_id) references ar_ap.credit_note (org_id, id) on delete cascade,
  foreign key (org_id, invoice_id) references ar_ap.invoice (org_id, id)
);
create index credit_application_invoice_idx on ar_ap.credit_application (invoice_id);

do $$
declare t text;
begin
  foreach t in array array['credit_note', 'credit_note_line', 'credit_application'] loop
    execute format('alter table ar_ap.%I enable row level security', t);
    execute format('alter table ar_ap.%I force row level security', t);
    execute format('create policy %I on ar_ap.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
