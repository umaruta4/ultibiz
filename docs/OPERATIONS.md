# UltiBiz — Operations (server1.ahadmart.com)

Deployment layout as installed 2026-11-25. Everything runs as user `sufehmi`
except the MariaDB container (docker) and Maven/JDK (root-owned, shared).

## Components

| Component | Where | Port | Notes |
|---|---|---|---|
| Tomcat 11.0.29 | `/opt/tomcat-11` (root-owned CATALINA_HOME) | `10.10.70.12:18080` HTTP, `127.0.0.1:18005` shutdown | systemd unit `ultibiz-tomcat.service` |
| CATALINA_BASE | `/home/sufehmi/tomcat-ultibiz` | — | conf/, webapps/, logs/, JVM opts in `bin/setenv.sh` |
| App WAR | `webapps/ROOT.war` (context `/`) | — | rebuilt via `mvn -B verify`, copied over + `systemctl restart ultibiz-tomcat` |
| MariaDB 11.8 | docker container `ultibiz-mariadb` | `127.0.0.1:3311` | data volume `/home/sufehmi/data/ultibiz-mariadb`, mem cap 384 MB, buffer pool 128 M |
| Maven 3.9.16 | `/opt/maven` (`/usr/local/bin/mvn`) | — | build tool |
| JDK 21.0.13 (Temurin) | `/opt/jdk-21` | — | used for builds (`JAVA_HOME=/opt/jdk-21`); runtime uses system JRE 21 |
| Env/secrets | `/home/sufehmi/projects/ultibiz/.env` (mode 600) | — | `ULTIBIZ_DB_*`, `ULTIBIZ_ADMIN_PASSWORD`; loaded by the systemd unit (`EnvironmentFile=-`) |

## JVM tuning (setenv.sh)

```
-Xms96m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC
```

Measured after warm-up + GC: **321 MB RSS** (target ≤ 350 MB).

## Routine operations

```bash
# status / logs
systemctl status ultibiz-tomcat
journalctl -u ultibiz-tomcat -n 100 --no-pager
tail -f /home/sufehmi/tomcat-ultibiz/logs/localhost.$(date +%F).log

# redeploy (build + swap)
cd /home/sufehmi/projects/ultibiz
JAVA_HOME=/opt/jdk-21 mvn -B verify
cp ultibiz-web/target/ultibiz-web-*.war /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war
rm -rf /home/sufehmi/tomcat-ultibiz/webapps/ROOT
systemctl restart ultibiz-tomcat

# db shell
docker exec -it ultibiz-mariadb mariadb -u ultibiz -p"$(grep ULTIBIZ_DB_PASSWORD /home/sufehmi/projects/ultibiz/.env | cut -d= -f2)" ultibiz

# backup / restore
tools/backup.sh            # dumps to /home/sufehmi/backups, keeps 14
tools/restore.sh <file>    # restores a dump
```

## Health checks

- `http://10.10.70.12:18080/actuator/health` → `{"status":"UP", "groups":[...]}`
- `/actuator/health/liveness`, `/actuator/health/readiness` (k8s probes)
- `/actuator/prometheus` (metrics; admin-only in the security config)

## Exposure

Public access is via the operator-managed forwarder from
`ultibiz.ahadmart.com` → `http://10.10.70.12:18080/`. Tomcat binds the LAN
address (ADR-001 §5); it is **not** exposed on the public interface directly.

## Known host caveats

- `apt` on server1 has a pre-existing broken state (`libxml2-dev` vs sury
  `libxml2`) — do **not** run `apt --fix-broken install` casually; the PHP
  stack depends on the sury builds. Java was therefore installed as a
  tarball (`/opt/jdk-21`), not via apt.
- `.env` is git-ignored and must be excluded from any `rsync --delete`
  (use `--exclude '.env'`).
