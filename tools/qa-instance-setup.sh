#!/usr/bin/env bash
# Creates the quality-assurance instance of UltiBiz: a second Tomcat on its own
# port with its own scratch database. The production instance and the
# production database are never touched.
#
# Run it on the deploy host as root:
#   bash tools/qa-instance-setup.sh
#
# The environment of the instance (its own administrator password and its own
# sealing key) is written to a file of mode 600 outside the repository.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a; . "$ROOT/.env"; set +a

APP_USER="${ULTIBIZ_APP_USER:-$(stat -c '%U' "$ROOT")}"
CATALINA_HOME="${CATALINA_HOME:-/opt/tomcat-11}"
PROD_BASE="${ULTIBIZ_TOMCAT_BASE:-/home/$APP_USER/tomcat-ultibiz}"
QA_BASE="${ULTIBIZ_QA_BASE:-/home/$APP_USER/tomcat-ultibiz-qa}"
QA_PORT="${ULTIBIZ_QA_PORT:-18081}"
QA_DB="${ULTIBIZ_QA_DB:-ultibiz_qa}"
QA_ENV="${ULTIBIZ_QA_ENV:-/home/$APP_USER/ultibiz-qa.env}"
CONTAINER="${ULTIBIZ_MARIADB_CONTAINER:-ultibiz-mariadb}"

echo "== the scratch database of the quality assurance =="
docker exec -e MYSQL_PWD="$MARIADB_ROOT_PASSWORD" "$CONTAINER" mariadb -uroot -e "
  CREATE DATABASE IF NOT EXISTS $QA_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  GRANT ALL PRIVILEGES ON $QA_DB.* TO 'ultibiz'@'%';
  FLUSH PRIVILEGES;"
docker exec -e MYSQL_PWD="$MARIADB_ROOT_PASSWORD" "$CONTAINER" mariadb -uroot -N \
  -e "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('ultibiz','$QA_DB');"

echo "== the environment file of the instance =="
grep -v -E '^(ULTIBIZ_DB_NAME|ULTIBIZ_ADMIN_PASSWORD|ULTIBIZ_AI_ENCRYPTION_KEY|ULTIBIZ_REPORT_DIR|ULTIBIZ_SSO_ENABLED|SPRING_PROFILES_ACTIVE|ULTIBIZ_SSO_[A-Z_]*|MARIADB_ROOT_PASSWORD)=' .env > "$QA_ENV"
{
  echo "MARIADB_ROOT_PASSWORD=$MARIADB_ROOT_PASSWORD"
  echo "ULTIBIZ_DB_NAME=$QA_DB"
  echo "ULTIBIZ_ADMIN_PASSWORD=$(openssl rand -hex 12)"
  echo "ULTIBIZ_AI_ENCRYPTION_KEY=$(openssl rand -hex 32)"
  echo "ULTIBIZ_REPORT_DIR=/home/$APP_USER/ultibiz-qa-reports"
  echo "ULTIBIZ_SSO_ENABLED=false"
  echo "ULTIBIZ_TENANT_MODE=single"
  # the secrets of the providers of the production are not copied
  echo "ULTIBIZ_SMTP_PASSWORD="
  echo "ULTIBIZ_EMAIL_WEBHOOK_SECRET="
  echo "ULTIBIZ_EXCHANGE_WEBHOOK_SECRET=$(openssl rand -hex 20)"
} >> "$QA_ENV"
chmod 600 "$QA_ENV"
mkdir -p "/home/$APP_USER/ultibiz-qa-reports"

echo "== the second tomcat =="
rm -rf "$QA_BASE"
mkdir -p "$QA_BASE"/webapps "$QA_BASE"/logs "$QA_BASE"/temp "$QA_BASE"/work "$QA_BASE"/lib
cp -r "$PROD_BASE/conf" "$QA_BASE/conf"
cp -r "$PROD_BASE/bin" "$QA_BASE/bin"
# the connector of the instance: its own port, the loopback only
sed -i -E "s|<Connector port=\"[0-9]+\" address=\"[^\"]*\"|<Connector port=\"$QA_PORT\" address=\"127.0.0.1\"|;
           s|<Server port=\"[0-9]+\"|<Server port=\"$((QA_PORT - 75))\"|" "$QA_BASE/conf/server.xml"
grep -nE 'Connector port|Server port' "$QA_BASE/conf/server.xml" | head -3
cp ultibiz-web/target/ultibiz-web-*.war "$QA_BASE/webapps/ROOT.war"
chown -R "$APP_USER:$APP_USER" "$QA_BASE" "/home/$APP_USER/ultibiz-qa-reports"

echo "== the unit of the instance =="
cat > /etc/systemd/system/ultibiz-qa.service <<UNIT
[Unit]
Description=UltiBiz Tomcat - the quality assurance instance (a scratch database)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
Environment=JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
Environment=CATALINA_HOME=$CATALINA_HOME
Environment=CATALINA_BASE=$QA_BASE
EnvironmentFile=-$QA_ENV
ExecStart=$CATALINA_HOME/bin/catalina.sh run
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable ultibiz-qa
systemctl restart ultibiz-qa
code=000
for i in $(seq 1 60); do
  sleep 3
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$QA_PORT/login" || true)
  [ "$code" = "200" ] && break
done
echo "the login of the quality assurance instance: $code"
[ "$code" = "200" ] || exit 1
echo "run the end-to-end checks: python3 tools/qa-e2e.py"
