-- Spec 056. The letterhead: where an entity is, how to reach it, how to pay it, and its logo. One row per
-- entity, all of it optional, read by every document Solid sends out.

create table org.branding (
  entity_id            uuid primary key,
  org_id               uuid not null,
  address              text check (address is null or length(address) <= 400),
  phone                text check (phone is null or length(phone) <= 60),
  email                text check (email is null or length(email) <= 200),
  website              text check (website is null or length(website) <= 200),
  tax_id               text check (tax_id is null or length(tax_id) <= 60),
  payment_instructions text check (payment_instructions is null or length(payment_instructions) <= 500),
  -- The entity's own mark, not a customer document: it belongs here rather than in the encrypted vault.
  logo_bytes           bytea check (logo_bytes is null or length(logo_bytes) <= 1048576),
  logo_content_type    text check (logo_content_type in ('image/png', 'image/jpeg')),
  updated_at           timestamptz not null default now(),
  unique (org_id, entity_id),
  foreign key (org_id, entity_id) references org.entity (org_id, id) on delete cascade,
  check ((logo_bytes is null) = (logo_content_type is null))
);

alter table org.branding enable row level security;
alter table org.branding force row level security;
create policy branding_org_isolation on org.branding
  using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id());
