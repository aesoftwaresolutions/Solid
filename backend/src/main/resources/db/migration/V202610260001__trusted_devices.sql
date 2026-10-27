-- Spec 070. A trusted device skips the authenticator code at sign-in for 30 days. Only the SHA-256 of the
-- device token is stored — a stolen row is useless elsewhere — exactly the discipline invitations,
-- password resets and sessions already follow. Identity spans organizations, so like iam.session this table
-- is not org-scoped (see the RowLevelSecurityTests allowlist, updated in the same slice).

create table iam.trusted_device (
  id           uuid primary key,
  user_id      uuid not null references iam.user_account (id),
  token_hash   text not null unique,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz not null default now(),
  expires_at   timestamptz not null,
  ip           text,
  user_agent   text
);
create index trusted_device_user_idx on iam.trusted_device (user_id);
create index trusted_device_expiry_idx on iam.trusted_device (expires_at);

-- How a session passed the second factor. A session promoted by a trusted device never typed a code, so it may
-- not mint a new device token: otherwise each month's cookie would renew the next and the code would never be
-- asked for again. (Defaults to false: every existing session proved its code the ordinary way.)
alter table iam.session add column mfa_via_device boolean not null default false;
