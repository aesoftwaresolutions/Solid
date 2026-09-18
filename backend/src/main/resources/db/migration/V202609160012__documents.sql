create table doc.document (
  id           uuid primary key,
  org_id       uuid not null,
  entity_id    uuid not null,
  filename     text not null check (length(trim(filename)) between 1 and 255),
  content_type text not null check (length(content_type) <= 120),
  size_bytes   bigint not null check (size_bytes > 0),
  sha256       char(64) not null,
  storage_key  text not null unique,
  kind         text not null check (kind in ('receipt', 'bank_statement', 'w2', 'form_1099', 'invoice', 'bill',
                                             'contract', 'other')),
  note         text check (note is null or length(note) <= 500),
  uploaded_by  uuid,
  uploaded_at  timestamptz not null default now(),
  unique (org_id, id),
  unique (entity_id, sha256),
  foreign key (org_id, entity_id) references org.entity (org_id, id)
);
create index document_entity_kind on doc.document (entity_id, kind, uploaded_at desc);

create table doc.document_link (
  document_id uuid not null,
  object_type text not null check (object_type in ('journal_entry', 'bank_transaction', 'invoice', 'bill', 'asset')),
  object_id   uuid not null,
  org_id      uuid not null,
  created_at  timestamptz not null default now(),
  primary key (document_id, object_type, object_id),
  foreign key (org_id, document_id) references doc.document (org_id, id) on delete cascade
);
create index document_link_object on doc.document_link (object_type, object_id);

do $$
declare t text;
begin
  foreach t in array array['document', 'document_link'] loop
    execute format('alter table doc.%I enable row level security', t);
    execute format('alter table doc.%I force row level security', t);
    execute format('create policy %I on doc.%I using (org_id = sys.current_org_id()) with check (org_id = sys.current_org_id())',
                   t || '_org_isolation', t);
  end loop;
end $$;
