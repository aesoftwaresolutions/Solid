#!/usr/bin/env bash
# Restores a backup made by ops/backup.sh into a stopped Solid instance.
#
#   ./ops/restore.sh backups/solid-20260918-020000 [--force]
#
# Refuses to overwrite a database that already holds Solid tables unless --force is given.
# Before it starts it prints the backup's master-key fingerprint: it must match the SOLID_MASTER_KEY on this
# server, or the restored documents and MFA secrets will be unreadable and the app will refuse to start.
set -euo pipefail

SOURCE="${1:-}"
FORCE="${2:-}"
COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${SOLID_DB_USER:-solid}"
DB_NAME="${SOLID_DB_NAME:-solid}"
DOCUMENTS_VOLUME="${SOLID_DOCUMENTS_VOLUME:-documents}"

if [ -z "${SOURCE}" ] || [ ! -f "${SOURCE}/database.sql.gz" ]; then
  echo "Usage: $0 <backup-directory> [--force]" >&2
  exit 2
fi

if [ -f "${SOURCE}/MANIFEST" ]; then
  echo "--- backup manifest ---"
  cat "${SOURCE}/MANIFEST"
  echo "-----------------------"
  echo "Check key_fingerprint above against the SOLID_MASTER_KEY in this server's .env before continuing."
fi

echo "Stopping the application (the database keeps running)…"
${COMPOSE} stop app web || true

EXISTING="$(${COMPOSE} exec -T postgres psql -qtAX -U "${DB_USER}" -d "${DB_NAME}" \
  -c "select count(*) from information_schema.tables where table_schema in ('org','gl','iam','doc')" | tr -d '[:space:]')"
if [ "${EXISTING}" != "0" ] && [ "${FORCE}" != "--force" ]; then
  echo "This database already holds ${EXISTING} Solid table(s). Re-run with --force to overwrite it." >&2
  exit 1
fi

echo "Restoring the database…"
gunzip -c "${SOURCE}/database.sql.gz" | ${COMPOSE} exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USER}" -d "${DB_NAME}"

if [ -f "${SOURCE}/documents.tar.gz" ]; then
  echo "Restoring the document vault…"
  docker run --rm -v "${DOCUMENTS_VOLUME}:/data" -v "$(cd "${SOURCE}" && pwd):/backup:ro" alpine:3 \
    sh -c 'rm -rf /data/* && tar xzf /backup/documents.tar.gz -C /data'
fi

echo "Starting the application…"
${COMPOSE} up -d

echo "Restore finished. If the app exits with a message about SOLID_MASTER_KEY, this backup belongs to a"
echo "different key — put that key back in .env rather than deleting the check."
