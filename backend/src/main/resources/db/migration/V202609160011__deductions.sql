create table pf.vehicle (
  id              uuid primary key,
  org_id          uuid not null,
  entity_id       uuid not null,
  name            text not null check (length(trim(name)) between 1 and 120),
  description     text check (description is null or length(description) <= 300),
  in_service_date date,
  is_archived     boolean not null default false,
  created_at      timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, name),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);

create table pf.mileage_trip (
  id             uuid primary key,
  org_id         uuid not null,
  entity_id      uuid not null,
  vehicle_id     uuid not null,
  trip_date      date not null,
  miles          numeric(7, 1) not null check (miles > 0 and miles <= 10000),
  category       text not null check (category in ('business', 'commuting', 'personal', 'charity', 'medical')),
  purpose        text check (purpose is null or length(purpose) <= 300),
  start_location text check (start_location is null or length(start_location) <= 200),
  end_location   text check (end_location is null or length(end_location) <= 200),
  created_at     timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  foreign key (org_id, vehicle_id) references pf.vehicle (org_id, id),
  -- The IRS expects a contemporaneous record of the business purpose of each trip.
  check (category <> 'business' or (purpose is not null and length(trim(purpose)) > 0))
);
create index mileage_trip_entity_date on pf.mileage_trip (entity_id, trip_date);

create table pf.home_office (
  entity_id                uuid not null,
  tax_year                 smallint not null check (tax_year between 2000 and 2100),
  org_id                   uuid not null,
  method                   text not null check (method in ('simplified')),
  total_home_square_feet   int not null check (total_home_square_feet > 0 and total_home_square_feet <= 100000),
  office_square_feet       int not null check (office_square_feet > 0 and office_square_feet <= 100000),
  months_used              smallint check (months_used between 1 and 12),
  updated_at               timestamptz not null default now(),
  primary key (entity_id, tax_year),
  foreign key (org_id, entity_id) references org.entity (org_id, id),
  check (office_square_feet <= total_home_square_feet)
);

do $$
declare t text;
begin
  foreach t in array array['vehicle', 'mileage_trip', 'home_office'] loop
    execute format('alter table pf.%I enable row level security', t);
    execute format('alter table pf.%I force row level security', t);
    execute format('create policy %I on pf.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
