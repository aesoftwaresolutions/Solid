-- Spec 054. A quote is a price you offered, not money anyone owes: it has no journal entry, and nothing in
-- it reaches a report until it has been converted into an invoice and that invoice issued.

create table ar_ap.quote (
  id               uuid primary key,
  org_id           uuid not null,
  entity_id        uuid not null,
  customer_id      uuid not null,
  quote_number     text not null check (length(trim(quote_number)) between 1 and 40),
  issue_date       date not null,
  valid_until      date,
  memo             text check (memo is null or length(memo) <= 500),
  total_minor      bigint not null default 0 check (total_minor >= 0),
  currency         char(3) not null,
  status           text not null check (status in ('draft', 'sent', 'accepted', 'declined', 'converted')),
  invoice_id       uuid,
  declined_reason  text check (declined_reason is null or length(declined_reason) <= 500),
  created_at       timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, quote_number),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, customer_id) references ar_ap.customer (org_id, id),
  foreign key (org_id, invoice_id) references ar_ap.invoice (org_id, id),
  check (valid_until is null or valid_until >= issue_date),
  -- A converted quote must say which invoice it became; nothing else may point at one.
  check ((status = 'converted') = (invoice_id is not null))
);
create index quote_entity_idx on ar_ap.quote (entity_id, issue_date desc);

create table ar_ap.quote_line (
  id                uuid primary key,
  org_id            uuid not null,
  quote_id          uuid not null,
  line_no           smallint not null check (line_no >= 1),
  description       text not null check (length(trim(description)) between 1 and 300),
  quantity          numeric(12, 4) not null check (quantity > 0),
  unit_price_minor  bigint not null check (unit_price_minor >= 0),
  amount_minor      bigint not null check (amount_minor >= 0),
  income_account_id uuid not null,
  unique (quote_id, line_no),
  foreign key (org_id, quote_id) references ar_ap.quote (org_id, id) on delete cascade,
  foreign key (org_id, income_account_id) references gl.account (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['quote', 'quote_line'] loop
    execute format('alter table ar_ap.%I enable row level security', t);
    execute format('alter table ar_ap.%I force row level security', t);
    execute format('create policy %I on ar_ap.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
