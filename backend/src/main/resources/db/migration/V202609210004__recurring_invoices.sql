-- Spec 052. Invoices that repeat: a template, its lines, and one row per occurrence actually created.
-- Nothing here runs on a timer; a person or a cron job calling the API creates what is due (see spec 026 for
-- the same design on journal entries).

create table ar_ap.recurring_invoice (
  id            uuid primary key,
  org_id        uuid not null,
  entity_id     uuid not null,
  customer_id   uuid not null,
  name          text not null check (length(trim(name)) between 1 and 120),
  memo          text check (memo is null or length(memo) <= 500),
  terms         text not null check (terms in ('due_on_receipt', 'net_15', 'net_30', 'net_60')),
  frequency     text not null check (frequency in ('monthly', 'quarterly', 'annual')),
  start_date    date not null,
  end_date      date,
  day_of_month  int not null check (day_of_month between 1 and 31),
  is_active     boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, customer_id) references ar_ap.customer (org_id, id),
  check (end_date is null or end_date >= start_date)
);
create index recurring_invoice_entity_idx on ar_ap.recurring_invoice (entity_id, name);

create table ar_ap.recurring_invoice_line (
  id                   uuid primary key,
  org_id               uuid not null,
  recurring_invoice_id uuid not null,
  line_no              smallint not null check (line_no >= 1),
  description          text not null check (length(trim(description)) between 1 and 300),
  quantity             numeric(12, 4) not null check (quantity > 0),
  unit_price_minor     bigint not null check (unit_price_minor >= 0),
  income_account_id    uuid not null,
  tax_rate_id          uuid,
  unique (recurring_invoice_id, line_no),
  foreign key (org_id, recurring_invoice_id) references ar_ap.recurring_invoice (org_id, id) on delete cascade,
  foreign key (org_id, income_account_id) references gl.account (org_id, id)
);

-- One row per occurrence created; the primary key is what makes re-running a range harmless.
create table ar_ap.recurring_invoice_occurrence (
  recurring_invoice_id uuid not null,
  occurrence_date      date not null,
  invoice_id           uuid not null,
  org_id               uuid not null,
  created_at           timestamptz not null default now(),
  primary key (recurring_invoice_id, occurrence_date),
  foreign key (org_id, recurring_invoice_id) references ar_ap.recurring_invoice (org_id, id) on delete cascade,
  foreign key (org_id, invoice_id) references ar_ap.invoice (org_id, id)
);

do $$
declare t text;
begin
  foreach t in array array['recurring_invoice', 'recurring_invoice_line', 'recurring_invoice_occurrence'] loop
    execute format('alter table ar_ap.%I enable row level security', t);
    execute format('alter table ar_ap.%I force row level security', t);
    execute format('create policy %I on ar_ap.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
