create table gl.account (
  id                    uuid primary key,
  org_id                uuid not null,
  entity_id             uuid not null,
  code                  text not null check (code ~ '^[A-Za-z0-9.\-]{1,20}$'),
  name                  text not null check (length(trim(name)) between 1 and 120),
  type                  text not null check (type in ('asset', 'liability', 'equity', 'income', 'expense')),
  subtype               text check (subtype is null or subtype ~ '^[a-z_]{1,40}$'),
  parent_id             uuid,
  is_header             boolean not null default false,
  default_tax_line_code text,
  is_archived           boolean not null default false,
  created_at            timestamptz not null default now(),
  unique (entity_id, code),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, parent_id) references gl.account (org_id, id),
  check (parent_id is null or parent_id <> id),
  check (not (is_header and default_tax_line_code is not null)),
  check (type in ('income', 'expense') or default_tax_line_code is null)
);
create index account_entity_idx on gl.account (entity_id, code);

alter table gl.account enable row level security;
alter table gl.account force row level security;
create policy account_org_isolation on gl.account
  using (org_id = sys.current_org_id())
  with check (org_id = sys.current_org_id());
