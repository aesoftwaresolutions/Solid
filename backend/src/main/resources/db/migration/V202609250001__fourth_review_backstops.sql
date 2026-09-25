-- Spec 065, row 15: rules the application already keeps, now kept by the database as well, so that a future
-- code path, script or bug cannot quietly break them. The constraints are NOT VALID: they bind every row
-- written from now on without refusing to upgrade an install whose older rows predate them.

-- A credit note line refers to an invoice line and a tax rate of its own organization. The old foreign key
-- named the invoice line alone, and foreign keys are checked past row-level security, so on its own it would
-- have accepted another organization's line.
alter table ar_ap.invoice_line add constraint invoice_line_org_id_id_key unique (org_id, id);

alter table ar_ap.credit_note_line drop constraint credit_note_line_invoice_line_fk;
alter table ar_ap.credit_note_line
  add constraint credit_note_line_invoice_line_fk
    foreign key (org_id, invoice_line_id) references ar_ap.invoice_line (org_id, id) not valid,
  add constraint credit_note_line_tax_rate_fk
    foreign key (org_id, tax_rate_id) references stx.tax_rate (org_id, id) not valid;

alter table ar_ap.recurring_invoice_line
  add constraint recurring_invoice_line_tax_rate_fk
    foreign key (org_id, tax_rate_id) references stx.tax_rate (org_id, id) not valid;

-- The instance row is written once, on first start. Its key fingerprint is what catches a database restored
-- beside the wrong master key, so the application must not be able to rewrite or remove it.
revoke update, delete, truncate on sys.instance from solid_app;

-- A recurring journal line of zero can never post, exactly as a journal line of zero cannot.
alter table gl.recurring_line
  add constraint recurring_line_amount_not_zero check (amount_minor <> 0) not valid;
