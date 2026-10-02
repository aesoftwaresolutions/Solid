-- Spec 069: business lines. A business line names one facet of a business (web design, automation, a product)
-- so the books can say what each facet earns and costs. It is a management dimension only: it never changes an
-- account, a tax line or a total.

create table gl.business_line (
  id          uuid primary key,
  org_id      uuid not null,
  entity_id   uuid not null,
  name        text not null check (length(trim(name)) between 1 and 60),
  is_archived boolean not null default false,
  created_at  timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);
-- Two lines of one entity cannot share a name, whatever the capitals.
create unique index business_line_entity_name on gl.business_line (entity_id, lower(trim(name)));

alter table gl.business_line enable row level security;
alter table gl.business_line force row level security;
create policy business_line_org_isolation on gl.business_line
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());

-- A business line is never deleted once it exists: postings may point at it. Archiving hides it from new work.
revoke delete, truncate on gl.business_line from solid_app;

-- Where the dimension is recorded. Nullable everywhere: no business line means shared, or not yet assigned.
alter table gl.journal_line add column business_line_id uuid,
  add constraint journal_line_business_line_fk
    foreign key (org_id, business_line_id) references gl.business_line (org_id, id);
create index journal_line_business_line on gl.journal_line (business_line_id) where business_line_id is not null;

alter table ar_ap.invoice add column business_line_id uuid,
  add constraint invoice_business_line_fk
    foreign key (org_id, business_line_id) references gl.business_line (org_id, id);

alter table ar_ap.bill add column business_line_id uuid,
  add constraint bill_business_line_fk
    foreign key (org_id, business_line_id) references gl.business_line (org_id, id);

-- A journal line may only carry a business line of its own entry's entity. The foreign key alone would accept
-- another entity of the same organization.
create or replace function gl.check_line_business_line() returns trigger
  language plpgsql as $$
declare
  line_entity uuid;
  entry_entity uuid;
begin
  if new.business_line_id is null then
    return new;
  end if;
  select entity_id into line_entity from gl.business_line where id = new.business_line_id;
  select entity_id into entry_entity from gl.journal_entry where id = new.journal_entry_id;
  if line_entity is distinct from entry_entity then
    raise exception 'Business line % does not belong to the entity of journal entry %',
      new.business_line_id, new.journal_entry_id using errcode = 'P0001';
  end if;
  return new;
end $$;

create trigger journal_line_business_line_entity
  before insert or update on gl.journal_line
  for each row execute function gl.check_line_business_line();
