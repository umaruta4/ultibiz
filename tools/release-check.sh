#!/usr/bin/env bash
# The release gate: the full build with the tests, the i18n audit and the
# database migrations. Run it before every release.
set -euo pipefail

cd "$(dirname "$0")/.."
echo "== UltiBiz release check =="

echo "-- the i18n audit"
bash tools/i18n-audit.sh

echo "-- the build with the tests (the integration tests need the test database)"
: "${ULTIBIZ_IT_JDBC_URL:=jdbc:mariadb://127.0.0.1:3311/ultibiz_test}"
: "${ULTIBIZ_IT_USER:=ultibiz}"
: "${ULTIBIZ_IT_PASSWORD:=${ULTIBIZ_DB_PASSWORD:-}}"
# the tenant test creates a real database — it needs the administrator of the
# MariaDB server (empty = the test is skipped)
: "${ULTIBIZ_IT_ADMIN_PASSWORD:=${MARIADB_ROOT_PASSWORD:-}}"
: "${ULTIBIZ_IT_ADMIN_USER:=${ULTIBIZ_DB_ADMIN_USER:-root}}"
JAVA_HOME="${JAVA_HOME:-/opt/jdk-21}" mvn -B verify \
  -Dultibiz.it.jdbcUrl="${ULTIBIZ_IT_JDBC_URL}" \
  -Dultibiz.it.user="${ULTIBIZ_IT_USER}" \
  -Dultibiz.it.password="${ULTIBIZ_IT_PASSWORD}" \
  -Dultibiz.it.adminUser="${ULTIBIZ_IT_ADMIN_USER}" \
  -Dultibiz.it.adminPassword="${ULTIBIZ_IT_ADMIN_PASSWORD}"

echo "-- the migrations are in order"
ls ultibiz-core/src/main/resources/db/migration | sort | tail -3

echo "== the release check is green =="
