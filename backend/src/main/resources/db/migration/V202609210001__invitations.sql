-- Spec 046. Invitations are how a second person gets an account on an instance whose sign-up is closed.
-- Like the rest of iam, this table is not org-scoped by RLS: identity is global and access is enforced by
-- the application (see the RowLevelSecurityTests allowlist). Only the hash of the token is stored, so a
-- stolen database hands over no usable invitation.

create table iam.invitation (
  id            uuid primary key,
  org_id        uuid not null references org.organization (id),
  email         text not null check (email = lower(email) and length(email) between 3 and 254),
  role          text not null check (role in ('owner', 'admin', 'accountant', 'bookkeeper', 'viewer')),
  token_hash    text not null unique,
  invited_by    uuid references iam.user_account (id),
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,
  accepted_at   timestamptz,
  accepted_by   uuid references iam.user_account (id),
  revoked_at    timestamptz,
  check (accepted_at is null or revoked_at is null),
  check (accepted_at is null or accepted_by is not null)
);
create index invitation_org_idx on iam.invitation (org_id, created_at desc);

-- One live invitation per address per organization: re-inviting revokes the old one first, so a person can
-- never hold two valid tokens for the same books.
create unique index invitation_one_live_per_email
  on iam.invitation (org_id, email)
  where accepted_at is null and revoked_at is null;
