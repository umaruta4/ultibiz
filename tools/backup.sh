#!/usr/bin/env bash
# backup.sh — dump the ultibiz MariaDB database to a timestamped .sql.gz in
# /home/sufehmi/backups and keep the most recent 14 backups.
set -euo pipefail

CONTAINER="${ULTIBIZ_DB_CONTAINER:-ultibiz-mariadb}"
DB_NAME="${ULTIBIZ_DB_NAME:-ultibiz}"
BACKUP_DIR="${ULTIBIZ_BACKUP_DIR:-/home/sufehmi/backups}"
KEEP=14

usage() {
  echo "Usage: $0" >&2
  echo "Dumps database '${DB_NAME}' from container '${CONTAINER}' into ${BACKUP_DIR}." >&2
  echo "Overrides: ULTIBIZ_DB_CONTAINER, ULTIBIZ_DB_NAME, ULTIBIZ_BACKUP_DIR env vars." >&2
  exit "${1:-1}"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage 0
fi
if [[ $# -gt 0 ]]; then
  usage 1
fi

mkdir -p "${BACKUP_DIR}"

ts="$(date +%Y%m%d-%H%M%S)"
out="${BACKUP_DIR}/ultibiz-${ts}.sql.gz"

echo "Dumping ${DB_NAME} from ${CONTAINER} -> ${out}"
docker exec "${CONTAINER}" mariadb-dump \
  --user=root --password="${MARIADB_ROOT_PASSWORD:-$(docker exec "${CONTAINER}" printenv MARIADB_ROOT_PASSWORD)}" \
  --single-transaction --routines --triggers --databases "${DB_NAME}" \
  | gzip -9 > "${out}"

echo "Backup written: ${out} ($(du -h "${out}" | cut -f1))"

# Retain only the newest ${KEEP} backups.
ls -1t "${BACKUP_DIR}"/ultibiz-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  echo "Pruning old backup: ${old}"
  rm -f "${old}"
done
