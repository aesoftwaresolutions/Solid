-- Spec 048. One-time password reset tokens. Like the rest of iam this is not org-scoped: identity is global,
-- and a reset is used by someone who cannot sign in, so no organization scope exists at the time. Only the
-- SHA-256 of the token is stored, so the table is useless to anyone who steals it.

create table iam.password_reset (
  id          uuid primary key,
  user_id     uuid not null references iam.user_account (id),
  token_hash  text not null unique,
  issued_by   uuid references iam.user_account (id),   -- null when issued from the command line
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  used_at     timestamptz
);
create index password_reset_user_idx on iam.password_reset (user_id, created_at desc);
