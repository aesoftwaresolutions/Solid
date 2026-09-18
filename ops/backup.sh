#!/usr/bin/env bash
# Backs up one Solid instance: the database and the encrypted document vault.
#
#   ./ops/backup.sh [destination-directory]      (default: ./backups)
#
# Run it from the folder that holds docker-compose.yml. It writes one timestamped folder:
#   <destination>/solid-YYYYmmdd-HHMMSS/
#     database.sql.gz     pg_dump of the whole database
#     documents.tar.gz    the document vault, still encrypted
#     MANIFEST            what this backup is, including the master-key fingerprint
#
# IMPORTANT: the backup does NOT contain SOLID_MASTER_KEY, and restoring it next to a different key leaves
# every uploaded document and MFA secret unreadable. Keep the key somewhere else (a password manager), and
# keep it forever.
set -euo pipefail

DESTINATION="${1:-./backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="${DESTINATION}/solid-${STAMP}"
COMPOSE="${COMPOSE:-docker compose}"
DB_USER="${SOLID_DB_USER:-solid}"
DB_NAME="${SOLID_DB_NAME:-solid}"
DOCUMENTS_VOLUME="${SOLID_DOCUMENTS_VOLUME:-documents}"

mkdir -p "${TARGET}"

echo "Dumping the database…"
${COMPOSE} exec -T postgres pg_dump --clean --if-exists -U "${DB_USER}" "${DB_NAME}" | gzip > "${TARGET}/database.sql.gz"

echo "Archiving the document vault…"
docker run --rm -v "${DOCUMENTS_VOLUME}:/data:ro" -v "$(cd "${TARGET}" && pwd):/backup" alpine:3 \
  tar czf /backup/documents.tar.gz -C /data .

FINGERPRINT="unknown"
if [ -n "${SOLID_MASTER_KEY:-}" ] && command -v openssl >/dev/null 2>&1; then
  # Same value the application stores in sys.instance: sha256("solid-instance-key-fingerprint" || key).
  FINGERPRINT="$( { printf '%s' 'solid-instance-key-fingerprint'; printf '%s' "${SOLID_MASTER_KEY}" | base64 -d; } \
    | openssl dgst -sha256 -r | cut -c1-16 )"
fi

{
  echo "instance: $(basename "$(pwd)")"
  echo "taken_at: $(date -Iseconds)"
  echo "database: ${DB_NAME}"
  echo "key_fingerprint: ${FINGERPRINT}"
  echo "database_bytes: $(wc -c < "${TARGET}/database.sql.gz")"
  echo "documents_bytes: $(wc -c < "${TARGET}/documents.tar.gz")"
  echo "note: restore this only on a server holding the same SOLID_MASTER_KEY"
} > "${TARGET}/MANIFEST"

echo "Done: ${TARGET}"
cat "${TARGET}/MANIFEST"
