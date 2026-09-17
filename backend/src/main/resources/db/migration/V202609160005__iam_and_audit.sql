-- Identity is global (a user can belong to many organizations), so these tables are not org-scoped by RLS.
-- Access is enforced by the application; see RowLevelSecurityTests allowlist.

create table iam.user_account (
  id                    uuid primary key,
  email                 text not null check (email = lower(email) and length(email) between 3 and 254),
  display_name          text not null check (length(trim(display_name)) between 1 and 120),
  password_hash         text not null,
  mfa_secret_encrypted  text,
  mfa_enabled           boolean not null default false,
  mfa_last_used_step    bigint,
  failed_login_count    int not null default 0,
  locked_until          timestamptz,
  is_instance_admin     boolean not null default false,
  created_at            timestamptz not null default now(),
  unique (email),
  check (not mfa_enabled or mfa_secret_encrypted is not null)
);

create table iam.session (
  id               uuid primary key,
  token_hash       text not null unique,
  user_id          uuid not null references iam.user_account (id),
  mfa_verified     boolean not null default false,
  failed_mfa_count int not null default 0,
  created_at       timestamptz not null default now(),
  last_seen_at     timestamptz not null default now(),
  expires_at       timestamptz not null,
  revoked_at       timestamptz,
  ip               text,
  user_agent       text
);
create index session_user_idx on iam.session (user_id);

create table iam.mfa_recovery_code (
  id         uuid primary key,
  user_id    uuid not null references iam.user_account (id),
  code_hash  text not null,
  used_at    timestamptz
);
create index recovery_code_user_idx on iam.mfa_recovery_code (user_id);

create table iam.membership (
  org_id     uuid not null references org.organization (id),
  user_id    uuid not null references iam.user_account (id),
  role       text not null check (role in ('owner', 'admin', 'accountant', 'bookkeeper', 'viewer')),
  created_at timestamptz not null default now(),
  primary key (org_id, user_id)
);
create index membership_user_idx on iam.membership (user_id);

-- Append-only, hash-chained audit log.
create table audit.event (
  seq          bigint generated always as identity primary key,
  id           uuid not null unique,
  occurred_at  timestamptz not null default now(),
  org_id       uuid,
  actor_user_id uuid,
  actor_ip     text,
  action       text not null check (action ~ '^[a-z_]{1,50}$'),
  object_type  text,
  object_id    uuid,
  details      jsonb not null default '{}'::jsonb,
  prev_hash    text not null,
  hash         text not null
);
create index audit_event_org_idx on audit.event (org_id, seq);

-- The app may add and read audit events but never change or remove them.
revoke update, delete, truncate on audit.event from solid_app;
grant usage on sequence audit.event_seq_seq to solid_app;

create or replace function audit.forbid_change() returns trigger
  language plpgsql as $$
begin
  raise exception 'Audit events are append-only' using errcode = 'P0001';
end $$;

create trigger audit_event_append_only
  before update or delete on audit.event
  for each row execute function audit.forbid_change();
