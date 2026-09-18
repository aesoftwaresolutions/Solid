# 018 — Backups, restore and instance identity

**Status:** Done · **Owner review:** Needed · **Security review:** Needed

## Goal
A self-hosted instance holds the only copy of someone's books. Make backups a documented, scripted routine, and
make the one failure that silently destroys data — restoring a database next to the **wrong** `SOLID_MASTER_KEY` —
impossible to miss.

## Scope
`platform` module (`sys` schema), plus operator scripts and documentation. No accounting logic changes.

## Why the key check matters
Encrypted MFA secrets and every uploaded document are encrypted with `SOLID_MASTER_KEY`. A database restored on a
server with a different key still starts, still logs people in, and then fails to decrypt — sometimes months later.
So the instance records a fingerprint of its key and refuses to start if the key changes.

## Data contracts
- `sys.instance` — one row: `id` (uuid), `master_key_fingerprint` (char(64)), `created_at`.
- `GET /api/v1/system/backup-status` (instance admin only) → what an operator needs before and after a restore:
  `{instanceId, keyFingerprint, schemaVersion, appVersion, databaseBytes, tableEstimates: {"org.entity": n, …},
  documents: {count, bytes}, documentsRoot, checkedAt}`
  Row counts come from PostgreSQL's own statistics (`pg_stat_user_tables.n_live_tup`), so they are **estimates** —
  enough to notice "my restore is missing half the ledger", not an audit.

## Rules
- The fingerprint is `SHA-256("solid-instance-key-fingerprint" || key)` in hex — a one-way value that identifies a
  key without revealing it. Only its first 16 characters are ever displayed.
- On startup: no row → write one; row matches → continue; row differs → **fail to start** with a message naming the
  likely cause (a restore with the wrong key, or a rotated key) and pointing at `docs/operations.md`.
- `backup-status` is instance-admin only; org members get 403. It never reports anything derived from the key
  itself beyond the fingerprint.
- `ops/backup.sh` writes one timestamped folder holding `database.sql.gz` (pg_dump) and `documents.tar.gz`, plus a
  `MANIFEST` naming the schema version and key fingerprint. It never writes the key itself into the backup.
- `ops/restore.sh` refuses to run against a non-empty database unless `--force` is passed, and prints the manifest's
  fingerprint so the operator can compare it with their key before starting the app.

## Acceptance criteria
1. A fresh database gets exactly one `sys.instance` row, and a second startup with the same key leaves it unchanged.
2. Startup with a different key fails with a message that mentions the master key and the operations guide; the
   message contains no key material.
3. `GET /system/backup-status` returns the schema version, instance id, truncated fingerprint, database size and
   the document count and bytes on disk.
4. A member who is not an instance admin gets 403; an anonymous caller gets 401.
5. The fingerprint is stable across runs for the same key and different for a different key, and is not the key.
6. `ops/backup.sh` and `ops/restore.sh` pass `bash -n` and are documented in `docs/operations.md` with a restore
   drill and a schedule.

## Out of scope
Off-site upload (S3/B2), encrypted backup archives, point-in-time recovery, automatic scheduling inside the app
(cron on the VPS is the documented answer), key rotation and re-encryption — that is its own slice.
