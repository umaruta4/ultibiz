# UltiBiz

Open-source, modular ERP: Java 21, Spring Boot 4.2.0 (WAR on Apache Tomcat 11),
MariaDB 11.8+, Thymeleaf 3.1 + HTMX 2 + Bootstrap 5.3 (vendored — no CDN at
runtime, no Node/npm). Runs at root context (`/`).

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker + docker compose (for local stack and image builds)

## Quickstart

```bash
# 1. Build and verify
mvn -B verify

# 2. Run the full local stack (MariaDB + app on Tomcat)
tools/dev-up.sh
# -> http://127.0.0.1:18080/
```

`tools/dev-up.sh` copies `deploy/.env.example` to `deploy/.env` on first run —
edit the passwords there before continuing.

## Deploy the WAR to an existing Tomcat 11

```bash
mvn -B -DskipTests package
cp ultibiz-web/target/*.war /path/to/tomcat/webapps/ROOT.war
```

Set database credentials via `ULTIBIZ_DB_HOST`, `ULTIBIZ_DB_PORT`,
`ULTIBIZ_DB_NAME`, `ULTIBIZ_DB_USER`, `ULTIBIZ_DB_PASSWORD` environment
variables.

## Directory map

```
ultibiz-web/            Spring Boot web module (WAR)
  src/main/resources/
    templates/          Thymeleaf 3.1 templates (fragments/layout.html shell)
    static/css|js/      app.css, theme.js, app.js
    static/vendor/      vendored Bootstrap 5.3.3 + htmx.org 2.0.4 (+ SHA256SUMS)
    messages*.properties  i18n (en default, id)
deploy/
  Dockerfile            multi-stage: Maven build -> Tomcat 11 runtime
  docker-compose.yml    local stack: db (MariaDB 11.8) + app
  k8s/                  namespace, deployment, service, configmap, pdb, hpa,
                        migrations job
tools/
  vendor-assets.sh      download + checksum vendored front-end assets
  dev-up.sh             local stack bring-up with health wait
  backup.sh/restore.sh  MariaDB dump/restore via docker exec
.github/workflows/ci.yml  Maven verify/package + Docker image build
docs/                   ULTIBIZ_MASTER_PROMPT.md, ULTIBIZ_PHASE_PROMPTS.md
prompts/                authoring sources for docs/
```

## Specs and reporting

The full product spec lives in [docs/ULTIBIZ_MASTER_PROMPT.md](docs/ULTIBIZ_MASTER_PROMPT.md),
with per-phase build prompts in [docs/ULTIBIZ_PHASE_PROMPTS.md](docs/ULTIBIZ_PHASE_PROMPTS.md).

When reporting status on a phase, state: files created, verification performed
(build/test/health output), and deviations from the spec — never claim done on
unverified work.
