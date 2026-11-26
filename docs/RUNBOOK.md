# The UltiBiz runbook

The day-to-day operations of the production instance on `server1.ahadmart.com`.

## The services

| What | Where | How to check |
| --- | --- | --- |
| The application | Tomcat 11, `/home/sufehmi/tomcat-ultibiz`, systemd `ultibiz-tomcat` | `curl -s -o /dev/null -w "%{http_code}" http://10.10.70.12:18080/login` → 200 |
| The database | MariaDB in Docker, container `ultibiz-mariadb`, port 3311 | `docker exec ultibiz-mariadb mariadb -u ultibiz -p"$ULTIBIZ_DB_PASSWORD" -e "SELECT 1;" ultibiz` |
| The source | `/home/sufehmi/projects/ultibiz` (git, `main`) | `git -C /home/sufehmi/projects/ultibiz log --oneline -1` |
| The secrets | `/home/sufehmi/projects/ultibiz/.env` (mode 600, owner sufehmi) | `stat -c "%a %U" /home/sufehmi/projects/ultibiz/.env` |
| The e-mail settings | the interface: Administration → E-mail settings (`/admin/settings/email`) | `SELECT provider, from_address, is_active FROM email_provider_config;` |
| The reports | `/home/sufehmi/ultibiz-reports` | `ls -lt /home/sufehmi/ultibiz-reports | head` |
| The backups | `/var/backups/ultibiz` (the last 14 dumps) | `ls -lt /var/backups/ultibiz | head` |
| The logs | `journalctl -u ultibiz-tomcat` | `journalctl -u ultibiz-tomcat --since "1 hour ago" | grep -i error` |

## The deploy

```bash
cd /home/sufehmi/projects/ultibiz
git pull --ff-only
bash tools/release-check.sh          # the i18n audit + the build with the tests
sudo -u sufehmi JAVA_HOME=/opt/jdk-21 MAVEN_OPTS="-Xmx900m" mvn -B -DskipTests package
# the WAR is deployed with the rollback copy in place
cp /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war /home/sufehmi/tomcat-ultibiz/ROOT.war.bak
systemctl stop ultibiz-tomcat
rm -rf /home/sufehmi/tomcat-ultibiz/webapps/ROOT
cp ultibiz-web/target/ultibiz-web-0.1.0-SNAPSHOT.war /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war
chown sufehmi:sufehmi /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war
systemctl start ultibiz-tomcat
```

The migrations run automatically at the start (Flyway) — **always take a backup first**.

## The rollback

```bash
systemctl stop ultibiz-tomcat
rm -rf /home/sufehmi/tomcat-ultibiz/webapps/ROOT
cp /home/sufehmi/tomcat-ultibiz/ROOT.war.bak /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war
systemctl start ultibiz-tomcat
```

A rollback of the code does **not** roll back the database: the migrations are
forward only. When a migration has to be undone, write the compensating
migration (never edit an applied one).

## The backup and the restore drill

```bash
# the backup (as root, the docker socket belongs to root)
cd /home/sufehmi/projects/ultibiz && set -a && . .env && set +a
bash tools/backup.sh /var/backups/ultibiz

# the drill: a backup is restored into a scratch database and the counts are compared
DUMP=$(ls -1t /var/backups/ultibiz/ultibiz-*.sql.gz | head -1)
bash tools/restore-drill.sh "$DUMP"
```

The drill must be **green** before a release. A backup that was never restored
is not a backup.

## The schedulers

| Scheduler | What | Lock |
| --- | --- | --- |
| `InsightScheduler` | the anomaly rules (every 10 min) | `ultibiz-insight-run` |
| `ReportScheduler` | the scheduled reports (every 15 min) | `ultibiz-report-run` |
| `SlaScheduler` | the SLA monitor of the tickets (every 10 min) | `ultibiz-sla-run` |

Every scheduler takes a MariaDB named lock, so two replicas never run the same
job twice.

## The secrets

| Variable | Where it is used |
| --- | --- |
| `ULTIBIZ_ADMIN_PASSWORD` | the first administrator |
| `ULTIBIZ_DB_PASSWORD` | the database |
| `MARIADB_ROOT_PASSWORD` | the database administrator (the drill) |
| `ULTIBIZ_EXCHANGE_WEBHOOK_SECRET` | the inbound data-exchange webhook |
| `ULTIBIZ_AI_ENCRYPTION_KEY` | encrypts the AI provider keys |
| `ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK` | optional: the report delivery webhook |
| `ULTIBIZ_EMAIL_PROVIDER` | optional: `mandrill`/`mailchimp`, `sendgrid`, `mailgun` or `webhook` (the report e-mail via an API provider instead of SMTP) |
| `ULTIBIZ_MANDRILL_API_KEY` | the Mailchimp Transactional key (provider `mandrill`) |
| `ULTIBIZ_SENDGRID_API_KEY` | the SendGrid key (provider `sendgrid`) |
| `ULTIBIZ_MAILGUN_DOMAIN` / `ULTIBIZ_MAILGUN_API_KEY` | the Mailgun domain and key (provider `mailgun`); `ULTIBIZ_MAILGUN_REGION=eu` for the EU region |
| `ULTIBIZ_EMAIL_WEBHOOK_URL` / `ULTIBIZ_EMAIL_WEBHOOK_SECRET` | the generic e-mail webhook (provider `webhook`) |
| `ULTIBIZ_SMTP_HOST` / `ULTIBIZ_SMTP_USER` / `ULTIBIZ_SMTP_PASSWORD` / `ULTIBIZ_SMTP_FROM` | the SMTP relay (when no API provider is selected) |

A new value is written to `.env` (mode 600) and the service is restarted. Never
write a secret in the git repository, in a log or in a ticket.

The **e-mail settings** are the exception: they live in the database (the secrets
sealed with `ULTIBIZ_AI_ENCRYPTION_KEY`) and are changed by the administrator in
the interface — the environment variables are only the fallback.

## When something is wrong

| Symptom | First check | Then |
| --- | --- | --- |
| The application does not answer | `systemctl status ultibiz-tomcat` | the log: `journalctl -u ultibiz-tomcat -n 200` |
| The application answers 500 | the log (the Thymeleaf / SQL error) | the migration that failed: `flyway_schema_history` |
| The login is broken | the session table in the database | restart the service |
| A report is missing | `/home/sufehmi/ultibiz-reports` and the scheduler log | run the report by hand ("Run now") |
| A ticket is not escalated | the SLA scheduler log | the `sla_resolve_due` of the ticket |
| The database is slow | `docker stats ultibiz-mariadb` | the slow query log of the container |
