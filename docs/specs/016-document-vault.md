# 016 — Receipts & document vault

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
Keep the paperwork with the numbers: upload receipts, statements and tax documents, attach them to the transaction, invoice, bill or asset they belong to, and store them encrypted so a stolen disk or backup is useless.

## Scope
`docs` module (`doc` schema): upload, download, metadata, links to other records, and deletion rules. Files live on the instance's own disk (a Docker volume), encrypted with AES-256-GCM.

## Data contracts
Base `/api/v1/orgs/{orgId}/entities/{entityId}`
- `POST /documents` multipart `file` + `kind` (`receipt|bank_statement|w2|form_1099|invoice|bill|contract|other`) + optional `note` → 201 metadata
- `GET /documents?kind=&linkedType=&linkedId=` · `GET /documents/{id}` → metadata
- `GET /documents/{id}/content` → the decrypted bytes with the original filename and content type
- `POST /documents/{id}/links` `{objectType: journal_entry|bank_transaction|invoice|bill|asset, objectId}` · `DELETE /documents/{id}/links/{objectType}/{objectId}`
- `DELETE /documents/{id}` — only when it has no links

Metadata: `{id, filename, contentType, sizeBytes, sha256, kind, note, uploadedAt, uploadedBy, links:[{objectType, objectId}]}`

## Rules
- Maximum 25 MB per file. Accepted types, verified by **content sniffing** (not just the extension): PDF, PNG, JPEG, GIF, WebP, HEIC, plain text, CSV, OFX/QFX. Anything else → 400 `UNSUPPORTED_FILE_TYPE`.
- Files are encrypted at rest with AES-256-GCM using the instance master key (`SOLID_MASTER_KEY`); the ciphertext carries a random IV. Losing the key loses the files, which is stated in `.env.example`.
- The stored path never uses the original filename: `<root>/<orgId>/<yyyy>/<mm>/<documentId>`.
- Re-uploading identical bytes for the same entity returns the existing document (matched on SHA-256) instead of storing a second copy.
- Uploads and downloads are written to the audit log (`document_uploaded`, `document_downloaded`) because these files contain taxpayer data.

## Acceptance criteria
1. A PNG uploads, comes back byte-identical from `/content`, and the file on disk is **not** the plaintext (ciphertext differs and is longer).
2. A file whose bytes are not an accepted type is rejected even if named `.pdf`; a 26 MB file is rejected.
3. Re-uploading the same bytes returns the first document's id and does not create a second row or file.
4. Linking to a journal entry and a bank transaction works, duplicates are ignored, and `GET /documents?linkedType=journal_entry&linkedId=…` finds it.
5. Deleting a linked document is refused (409 `DOCUMENT_LINKED`); after unlinking it deletes both the row and the file on disk.
6. Audit events are recorded for upload and download, and never contain the file contents.
7. RLS on both tables; a member of another organization gets 404; viewers can download but not upload or delete.

## Out of scope
OCR / AI extraction (later slice, local Ollama), virus scanning, S3/MinIO backend, per-organization data keys (currently one instance key), thumbnails, full-text search.
