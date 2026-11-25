# UltiBiz

Open-source, modular ERP for multi-site manufacturing and distribution.
Java 21 · Spring Boot 4.2.0 (WAR on Apache Tomcat 11) · MariaDB 11.8+ ·
Thymeleaf 3.1 + HTMX 2 + Bootstrap 5.3 (vendored — no CDN at runtime, no
Node/npm/bundler). Runs at root context (`/`).

## Status

| Phase | Scope | State |
|---|---|---|
| **P0** | Foundation: Maven multi-module, schema baseline, security/login, DB-backed sessions, web shell, dark mode, audit, health probes, Flyway, deploy tooling | **done** |
| **P1** | Master data + multi-site (product, party, warehouse/rack/bin/dock, production line, work center), numbering engine, country/tax SPI, CSV import/export, master-data screens | **in progress** |
| P2 | Accounting (double entry, fiscal periods, statutory reports) | planned |
| P3 | Order management + manufacturing (BOM, work orders, routing) | planned |
| P4 | Asset management + HRM | planned |
| P5 | Projects + CRM + sales pipeline | planned |
| P6 | Dashboard + Knowledge Vault (RAG) + Helpdesk | planned |
| P7 | Data exchange: EDI, Peppol UBL, REST/JSON | planned |
| P8 | Offline sync, admin console, upgrades | planned |
| P9 | AI assistant, Kubernetes hardening, performance | planned |

## Modules

```
ultibiz-spi/     stable extension contracts (plugin descriptors, menu
                 contributions, tax rule provider) — no framework deps
ultibiz-core/    domain, jOOQ repositories, application services, Flyway
                 migrations, numbering engine, tax SPI implementation
ultibiz-web/     Spring MVC controllers, Thymeleaf views, security, i18n
                 (WAR packaging for Tomcat 11)
```

## Build & test

```bash
mvn -B verify                       # unit tests + WAR
```

Integration tests run against a real MariaDB and are opt-in (they need a
database, so they never run by accident in CI):

```bash
mvn -B verify \
  -Dultibiz.it.jdbcUrl=jdbc:mariadb://127.0.0.1:3311/ultibiz \
  -Dultibiz.it.user=ultibiz -Dultibiz.it.password=...
```

## Production deployment (server1.ahadmart.com)

| Piece | Where |
|---|---|
| Tomcat 11.0.29 | `CATALINA_HOME=/opt/tomcat-11`, `CATALINA_BASE=/home/sufehmi/tomcat-ultibiz` |
| App | `http://10.10.70.12:18080/` → forwarded from `https://ultibiz.ahadmart.com` |
| MariaDB 11.8.9 | docker container `ultibiz-mariadb`, `127.0.0.1:3311` |
| Service | `ultibiz-tomcat.service` (systemd, user `sufehmi`) |
| Secrets | `/home/sufehmi/projects/ultibiz/.env` (mode 600, git-ignored) |
| JVM budget | `-Xms96m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC` → ~320 MB RSS |

Redeploy:

```bash
cd /home/sufehmi/projects/ultibiz
JAVA_HOME=/opt/jdk-21 MAVEN_OPTS="-Xmx700m" mvn -B verify
cp ultibiz-web/target/ultibiz-web-*.war /home/sufehmi/tomcat-ultibiz/webapps/ROOT.war
rm -rf /home/sufehmi/tomcat-ultibiz/webapps/ROOT
systemctl restart ultibiz-tomcat
curl -s http://10.10.70.12:18080/actuator/health
```

`tools/install-server1.sh` provisions the host (Maven, Temurin JDK 21,
Tomcat 11, MariaDB container, systemd unit) idempotently.
Full operational notes: [`docs/OPERATIONS.md`](docs/OPERATIONS.md).
Architecture decisions: [`docs/adr/`](docs/adr/).

## Conventions

- **After every completed phase: update this README and push to GitHub.**
- The authoring prompts (`prompts/`, `docs/ULTIBIZ_*_PROMPT*.md`) are
  internal working documents and are deliberately **not** published to GitHub.
- No secret ever enters the repository; `.env` is server-side only and is
  excluded from every sync (`rsync --exclude '.env'`).
- Money and quantities are `DECIMAL`/`BigDecimal`, never floats; instants are
  UTC with an explicit zone at the edges.
- Every write is audited; every DDL change is a new Flyway script.
- Master data is soft-deleted (`deleted_at`) and version-checked (`row_version`).
- Site scoping is enforced in the repository layer — a user scoped to site A
  can never read or write site B rows.

## Directory map

```
ultibiz-web/src/main/resources/
  templates/          Thymeleaf views (fragments/layout.html shell + master data)
  static/css|js/      app.css, theme.js, app.js
  static/vendor/      vendored Bootstrap 5.3.3 + htmx 2.0.4 (+ SHA256SUMS)
  messages*.properties  i18n (en default, id)
ultibiz-core/src/main/resources/db/migration/
  V202611250001__ultibiz_baseline.sql   org/site/user/RBAC/audit + Spring Session
  V202611250002__p1_master_data.sql     master data, country/tax config, numbering
deploy/               Dockerfile, docker-compose, k8s manifests
tools/                install-server1.sh, dev-up.sh, backup.sh, restore.sh, vendor-assets.sh
docs/                 OPERATIONS.md, adr/
```
