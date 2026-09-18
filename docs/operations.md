# Running a Solid instance

This is the operator's guide for a self-hosted instance (for example a Hostinger VPS running Docker Compose).
Everything here assumes you are in the folder that holds `docker-compose.yml` and `.env`.

## The two things you must never lose

1. **The database** — the books themselves.
2. **`SOLID_MASTER_KEY`** — the 32-byte key in `.env` that encrypts MFA secrets and every uploaded document.

They are lost differently, so keep them in different places: the database in your backups, the key in a password
manager (and on paper, in a safe, if this is a business you care about). A backup **without** the key still gives you
the ledger, but the receipts and statements in it stay unreadable forever. There is no recovery path and no support
line that can undo it — that is the point of encrypting them.

Because that mistake is silent, the application checks for it. On its first start it records a one-way fingerprint of
the key in `sys.instance`. If it ever starts against a database whose fingerprint does not match the key it was given,
it refuses to start and says so. If you see that message, the fix is to put the original key back — not to delete the
row.

## Backups

```bash
./ops/backup.sh                 # writes ./backups/solid-YYYYmmdd-HHMMSS/
./ops/backup.sh /mnt/backups    # or somewhere else
```

Each run writes one folder holding `database.sql.gz`, `documents.tar.gz` and a `MANIFEST` naming the schema version,
sizes and the key fingerprint (never the key).

A reasonable schedule for a one-person business, via `crontab -e` on the VPS:

```cron
15 2 * * * cd /opt/solid && ./ops/backup.sh /mnt/backups >> /var/log/solid-backup.log 2>&1
30 3 * * 0 find /mnt/backups -maxdepth 1 -name 'solid-*' -mtime +35 -exec rm -rf {} +
```

Then copy `/mnt/backups` somewhere off the server — another provider, an external disk, anything that does not die
with this VPS. A backup that only exists on the machine it backs up is not a backup.

## Checking a backup without restoring it

Sign in as the instance administrator and open `GET /api/v1/system/backup-status`. It reports the instance id, the
key fingerprint (first 16 characters), the schema version, the database size, the number and size of stored
documents, and PostgreSQL's row estimates for the main tables. Note those numbers before a restore and compare them
after — a restore that lands with a fraction of the rows is easy to miss otherwise.

## Restoring

```bash
./ops/restore.sh backups/solid-20260918-020000
```

It prints the backup's manifest, stops the app, refuses to overwrite a database that still holds Solid tables
(pass `--force` when you mean it), loads the dump, replaces the document vault and starts the app again.

## The restore drill

Do this **before** you need it, once, and then once a year:

1. On a spare machine (or a second Compose project on the same box), copy `.env` — including the same
   `SOLID_MASTER_KEY` — and the latest backup folder.
2. `./ops/restore.sh <backup> --force`
3. Sign in, open a report, and download one uploaded receipt. If the receipt opens, the key and the vault came
   through. If the app refuses to start with the master-key message, your `.env` has the wrong key — find the right
   one now, while the original instance is still running.
4. Write down how long it took. That number is your real recovery time.

## Upgrades

```bash
git pull
docker compose up -d --build
```

Flyway applies new migrations at startup, so take a backup first (the step above takes seconds). Migrations are
append-only, so a newer database cannot be served by an older application — roll forward, or restore the backup.

## When the app will not start

- **"different SOLID_MASTER_KEY"** — the database and the key do not belong together. Restore the matching key.
- **Flyway validation errors** — the database was migrated by a newer version than the code you are running.
- **Database connection refused** — check `docker compose ps`; the app waits for PostgreSQL's health check.
