-- One row describing this installation. The fingerprint identifies the master key without revealing it, so a
-- database restored beside the wrong SOLID_MASTER_KEY is detected at startup instead of months later.
create table sys.instance (
  id                     uuid primary key,
  master_key_fingerprint char(64) not null,
  created_at             timestamptz not null default now(),
  -- Exactly one instance row is allowed.
  singleton              boolean not null default true unique check (singleton)
);

grant select, insert on sys.instance to solid_app;
