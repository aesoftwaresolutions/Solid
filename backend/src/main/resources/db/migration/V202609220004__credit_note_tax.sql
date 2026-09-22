-- Spec 058. Sales tax on a credit note, per the CPA ruling in docs/tax-sources/credit-note-sales-tax.md:
-- a full credit reverses the exact tax originally charged, a partial one reverses the credited amount at the
-- original rate. Both need to know which invoice line is being credited, so the line carries it.

alter table ar_ap.credit_note_line
  add column invoice_line_id   uuid,
  add column tax_rate_id       uuid,
  add column tax_amount_minor  bigint not null default 0 check (tax_amount_minor >= 0);

alter table ar_ap.credit_note_line
  add constraint credit_note_line_invoice_line_fk
  foreign key (invoice_line_id) references ar_ap.invoice_line (id),
  -- Tax only ever comes from a line being credited; there is nothing else for it to reverse.
  add constraint credit_note_line_tax_needs_origin
  check (tax_rate_id is null or invoice_line_id is not null);

create index credit_note_line_invoice_line_idx on ar_ap.credit_note_line (invoice_line_id);

alter table ar_ap.credit_note
  add column tax_total_minor bigint not null default 0 check (tax_total_minor >= 0);
