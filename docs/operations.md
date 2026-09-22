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

## Taking the data out

Backups are for restoring this server. If you want the books themselves in a form anything can read, sign in and
use **Reports → Export everything**, or `GET /api/v1/orgs/{orgId}/entities/{entityId}/export.zip`. It is a ZIP of
CSV files (accounts, journal, bank activity, invoices, bills and the document list) with a README explaining the
columns. The uploaded files themselves are not in it — they come out of the backup above.

## Upgrades

```bash
git pull
docker compose up -d --build
```

Flyway applies new migrations at startup, so take a backup first (the step above takes seconds). Migrations are
append-only, so a newer database cannot be served by an older application — roll forward, or restore the backup.

## When someone cannot sign in

Solid sends no email, so there is no "forgot password" message. There are two ways back in, in order of
preference:

1. **An instance administrator makes a link.** On the installation page, find the person under "People on
   this installation" and press *Make a reset link*. Copy it and give it to them however you already talk.
   It lasts an hour, works once, and does not turn off their authenticator app.
2. **Nobody can sign in at all** — the last administrator is locked out. Whoever has the server runs it once
   with the account's address:

   ```bash
   docker compose run --rm app java -jar /app/app.jar --solid.reset-password=you@example.com
   ```

   It prints a reset link and exits without serving anything. Open the link on the running instance.

Neither path touches two-factor authentication: signing in still needs the authenticator code, or one of the
recovery codes printed when it was set up. If those are gone too, the account cannot be recovered — make a
new one and move the membership across.

## Checking the stack actually works

After an upgrade — or after a restore, or any time you want to be sure — run the smoke test from outside:

```bash
python3 ops/smoke-test.py http://localhost:8080
```

It signs up a throwaway user, turns on MFA, creates a small organization, posts one entry and checks that the
profit & loss, the balance sheet, the cash-flow statement and the ledger's hash chain all agree with it. It
uses nothing but the public API, so a pass means the jar, the migrations, the reverse proxy and the API are
all working together — not merely that the build was green.

A failure prints the call that broke and what came back. The usual causes are the ones in the next section;
if the checks themselves disagree (a figure comes back wrong), stop and report it rather than upgrading
further, because that is a correctness problem, not a deployment one.

The test leaves its throwaway organization behind on purpose, so you can look at it. Remove it when you want
to; nothing else refers to it.

## When the app will not start

- **"different SOLID_MASTER_KEY"** — the database and the key do not belong together. Restore the matching key.
- **Flyway validation errors** — the database was migrated by a newer version than the code you are running.
- **Database connection refused** — check `docker compose ps`; the app waits for PostgreSQL's health check.
