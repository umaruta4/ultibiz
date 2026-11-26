#!/usr/bin/env bash
# The backup: the database (a consistent dump) and the report files.
# Usage: tools/backup.sh [target directory]
set -euo pipefail

TARGET="${1:-/var/backups/ultibiz}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "${TARGET}"

DB_NAME="${ULTIBIZ_DB_NAME:-ultibiz}"
DB_USER="${ULTIBIZ_DB_USER:-ultibiz}"
DB_PASSWORD="${ULTIBIZ_DB_PASSWORD:?the database password is required}"
DB_HOST="${ULTIBIZ_DB_HOST:-127.0.0.1}"
DB_PORT="${ULTIBIZ_DB_PORT:-3311}"

echo "-- the database dump"
docker exec ultibiz-mariadb mariadb-dump -u "${DB_USER}" -p"${DB_PASSWORD}" \
  --single-transaction --routines --triggers --events \
  "${DB_NAME}" | gzip > "${TARGET}/ultibiz-${STAMP}.sql.gz"

echo "-- the report files"
if [ -d "${ULTIBIZ_REPORT_DIR:-/home/sufehmi/ultibiz-reports}" ]; then
  tar czf "${TARGET}/reports-${STAMP}.tar.gz" -C "${ULTIBIZ_REPORT_DIR:-/home/sufehmi/ultibiz-reports}" .
fi

# keep the last 14 backups
ls -1t "${TARGET}"/ultibiz-*.sql.gz 2>/dev/null | tail -n +15 | xargs -r rm -f

echo "-- the backup is ready: ${TARGET}/ultibiz-${STAMP}.sql.gz"
ls -lh "${TARGET}"/ultibiz-${STAMP}.sql.gz
