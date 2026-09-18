create table ar_ap.vendor (
  id                         uuid primary key,
  org_id                     uuid not null,
  entity_id                  uuid not null,
  name                       text not null check (length(trim(name)) between 1 and 200),
  email                      text check (email is null or length(email) <= 254),
  phone                      text check (phone is null or length(phone) <= 40),
  address                    text check (address is null or length(address) <= 500),
  -- Only the last four digits of the TIN are stored here; the full value needs the encrypted
  -- field work planned with the tax module, so it is deliberately not collected yet.
  tax_id_last4               char(4) check (tax_id_last4 is null or tax_id_last4 ~ '^[0-9]{4}$'),
  tax_classification         text check (tax_classification is null or tax_classification in
                              ('individual', 'sole_proprietor', 'single_member_llc', 'partnership', 'c_corporation',
                               's_corporation', 'trust_estate', 'llc_c', 'llc_s', 'llc_p', 'other')),
  is_1099_vendor             boolean not null default false,
  default_expense_account_id uuid,
  is_archived                boolean not null default false,
  created_at                 timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, name),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, default_expense_account_id) references gl.account (org_id, id)
);

create table ar_ap.bill (
  id               uuid primary key,
  org_id           uuid not null,
  entity_id        uuid not null,
  vendor_id        uuid not null,
  vendor_reference text check (vendor_reference is null or length(vendor_reference) <= 60),
  bill_date        date not null,
  due_date         date not null,
  terms            text not null check (terms in ('due_on_receipt', 'net_15', 'net_30', 'net_60')),
  memo             text check (memo is null or length(memo) <= 500),
  total_minor      bigint not null check (total_minor >= 0),
  currency         char(3) not null,
  status           text not null check (status in ('draft', 'open', 'partially_paid', 'paid', 'void')),
  journal_entry_id uuid,
  created_at       timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, vendor_id) references ar_ap.vendor (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id),
  check (due_date >= bill_date),
  check ((status = 'draft') = (journal_entry_id is null))
);
create index bill_entity_status on ar_ap.bill (entity_id, status, due_date);

create table ar_ap.bill_line (
  id                 uuid primary key,
  org_id             uuid not null,
  bill_id            uuid not null,
  line_no            smallint not null check (line_no >= 1),
  description        text not null check (length(trim(description)) between 1 and 300),
  amount_minor       bigint not null check (amount_minor > 0),
  expense_account_id uuid not null,
  unique (bill_id, line_no),
  foreign key (org_id, bill_id) references ar_ap.bill (org_id, id) on delete cascade,
  foreign key (org_id, expense_account_id) references gl.account (org_id, id)
);

create table ar_ap.bill_payment (
  id                 uuid primary key,
  org_id             uuid not null,
  entity_id          uuid not null,
  vendor_id          uuid not null,
  paid_date          date not null,
  amount_minor       bigint not null check (amount_minor > 0),
  currency           char(3) not null,
  payment_account_id uuid not null,
  method             text check (method is null or length(method) <= 40),
  reference          text check (reference is null or length(reference) <= 100),
  journal_entry_id   uuid not null,
  created_at         timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, vendor_id) references ar_ap.vendor (org_id, id),
  foreign key (org_id, payment_account_id) references gl.account (org_id, id),
  foreign key (org_id, journal_entry_id) references gl.journal_entry (org_id, id)
);
create index bill_payment_vendor_date on ar_ap.bill_payment (vendor_id, paid_date);

create table ar_ap.bill_payment_application (
  id              uuid primary key,
  org_id          uuid not null,
  bill_payment_id uuid not null,
  bill_id         uuid not null,
  amount_minor    bigint not null check (amount_minor > 0),
  unique (bill_payment_id, bill_id),
  foreign key (org_id, bill_payment_id) references ar_ap.bill_payment (org_id, id) on delete cascade,
  foreign key (org_id, bill_id) references ar_ap.bill (org_id, id)
);
create index bill_payment_application_bill on ar_ap.bill_payment_application (bill_id);

do $$
declare t text;
begin
  foreach t in array array['vendor', 'bill', 'bill_line', 'bill_payment', 'bill_payment_application'] loop
    execute format('alter table ar_ap.%I enable row level security', t);
    execute format('alter table ar_ap.%I force row level security', t);
    execute format('create policy %I on ar_ap.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
