#!/usr/bin/env bash
# restore.sh — restore a .sql.gz backup created by backup.sh into the ultibiz
# MariaDB container.
set -euo pipefail

CONTAINER="${ULTIBIZ_DB_CONTAINER:-ultibiz-mariadb}"

usage() {
  echo "Usage: $0 <backup-file.sql.gz>" >&2
  echo "Restores the given backup into container '${CONTAINER}'." >&2
  echo "Overrides: ULTIBIZ_DB_CONTAINER env var." >&2
  exit "${1:-1}"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage 0
fi
if [[ $# -ne 1 ]]; then
  usage 1
fi

file="$1"
if [[ ! -f "${file}" ]]; then
  echo "Backup file not found: ${file}" >&2
  exit 1
fi

echo "Restoring ${file} into ${CONTAINER}..."
zcat "${file}" | docker exec -i "${CONTAINER}" mariadb \
  --user=root --password="${MARIADB_ROOT_PASSWORD:-$(docker exec "${CONTAINER}" printenv MARIADB_ROOT_PASSWORD)}"

echo "Restore complete."
