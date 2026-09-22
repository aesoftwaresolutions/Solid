-- Spec 049. Tax figures added at runtime by whoever administers the installation, each with the source it
-- came from. Instance-wide, like the rule files in the jar: a published IRS rate is not one organization's
-- private data, and the table holds no customer information. Rows are never updated in place or deleted —
-- a correction supersedes, so the trail of what was believed when stays intact.

create table tax.figure (
  id             uuid primary key,
  figure_key     text not null check (figure_key ~ '^[a-z0-9_]{3,60}$'),
  tax_year       int not null check (tax_year between 1913 and 2100),
  value_decimal  numeric(18, 6) not null,
  source         text not null check (length(trim(source)) >= 30),
  note           text check (note is null or length(note) <= 500),
  added_by       uuid references iam.user_account (id),
  added_at       timestamptz not null default now(),
  superseded_at  timestamptz,
  superseded_by  uuid references iam.user_account (id)
);
create index tax_figure_lookup_idx on tax.figure (figure_key, tax_year, superseded_at);

-- At most one figure in force per key and year; older ones stay, marked superseded.
create unique index tax_figure_one_in_force
  on tax.figure (figure_key, tax_year)
  where superseded_at is null;
