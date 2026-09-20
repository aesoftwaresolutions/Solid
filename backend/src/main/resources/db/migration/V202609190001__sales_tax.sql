-- Sales tax rates are entered by the user for their own jurisdictions. Solid never supplies a rate: they vary by
-- state, county, city and district and change often, and getting one wrong is the taxpayer's problem, not a
-- rounding detail. See docs/specs/037-sales-tax.md.
create table stx.tax_rate (
  id                   uuid primary key,
  org_id               uuid not null,
  entity_id            uuid not null,
  jurisdiction         text not null check (length(trim(jurisdiction)) between 1 and 120),
  rate_percent         numeric(7, 4) not null check (rate_percent >= 0 and rate_percent <= 100),
  liability_account_id uuid not null,
  effective_from       date not null,
  effective_to         date,
  note                 text check (note is null or length(note) <= 500),
  is_active            boolean not null default true,
  created_at           timestamptz not null default now(),
  unique (org_id, id),
  check (effective_to is null or effective_to >= effective_from),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, liability_account_id) references gl.account (org_id, id)
);
create index tax_rate_entity on stx.tax_rate (entity_id, is_active);

alter table stx.tax_rate enable row level security;
alter table stx.tax_rate force row level security;
create policy tax_rate_org_isolation on stx.tax_rate
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());

-- Tax charged on an invoice. Held per line, because rounding happens per line and a customer checks it that way.
alter table ar_ap.invoice_line add column tax_rate_id uuid;
alter table ar_ap.invoice_line add column tax_amount_minor bigint not null default 0
  check (tax_amount_minor >= 0);
alter table ar_ap.invoice_line add constraint invoice_line_tax_rate_fk
  foreign key (org_id, tax_rate_id) references stx.tax_rate (org_id, id);

alter table ar_ap.invoice add column tax_total_minor bigint not null default 0 check (tax_total_minor >= 0);
