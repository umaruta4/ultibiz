#!/usr/bin/env bash
# The restore drill: a backup is restored into a scratch database and the row
# counts are compared with the production ones. A backup that was never
# restored is not a backup.
set -euo pipefail

BACKUP="${1:?the path of the dump is required}"
DB_NAME="${ULTIBIZ_DRILL_DB:-ultibiz_restore_drill}"
DB_USER="${ULTIBIZ_DB_USER:-ultibiz}"
DB_PASSWORD="${ULTIBIZ_DB_PASSWORD:?the database password is required}"
# the scratch database is created by the administrator of the server
ADMIN_USER="${ULTIBIZ_DB_ADMIN_USER:-root}"
ADMIN_PASSWORD="${ULTIBIZ_DB_ADMIN_PASSWORD:-${MARIADB_ROOT_PASSWORD:-}}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?the administrator password is required}"

echo "-- the scratch database"
docker exec ultibiz-mariadb mariadb -u "${ADMIN_USER}" -p"${ADMIN_PASSWORD}" \
  -e "DROP DATABASE IF EXISTS ${DB_NAME}; CREATE DATABASE ${DB_NAME};
      GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'%'; FLUSH PRIVILEGES;"

echo "-- the restore"
gunzip -c "${BACKUP}" | docker exec -i ultibiz-mariadb mariadb -u "${DB_USER}" -p"${DB_PASSWORD}" \
  "${DB_NAME}"

echo "-- the counts"
for table in organization site party product sales_order journal_entry knowledge_document; do
  prod="$(docker exec ultibiz-mariadb mariadb -u "${DB_USER}" -p"${DB_PASSWORD}" -N -B \
    -e "SELECT COUNT(*) FROM ${table};" ultibiz 2>/dev/null || echo n/a)"
  drill="$(docker exec ultibiz-mariadb mariadb -u "${DB_USER}" -p"${DB_PASSWORD}" -N -B \
    -e "SELECT COUNT(*) FROM ${table};" "${DB_NAME}" 2>/dev/null || echo n/a)"
  printf "%-22s production=%-8s restored=%-8s\n" "${table}" "${prod}" "${drill}"
  if [ "${prod}" != "${drill}" ]; then
    echo "!! the counts differ for ${table}"
    exit 1
  fi
done

echo "-- the drill is green"
docker exec ultibiz-mariadb mariadb -u "${ADMIN_USER}" -p"${ADMIN_PASSWORD}" \
  -e "DROP DATABASE ${DB_NAME};"
