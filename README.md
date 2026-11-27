# UltiBiz

Open-source, modular ERP for multi-site manufacturing and distribution — from the
master data to the books, the warehouse, the factory, the service desk and the
assistant. Everything in one application, one database schema and one set of
roles.

Java 21 · Spring Boot 4.2.0 (WAR on Apache Tomcat 11) · MariaDB 11.8+ ·
Thymeleaf 3.1 + HTMX 2 + Bootstrap 5.3 (vendored — no CDN at runtime, no
Node/npm/bundler) · runs at the root context (`/`).

| | |
|---|---|
| Tests | 206 core + 9 web, green on every release |
| Schema | 19 Flyway migrations, applied in order, never edited afterwards |
| Identity | local login (RBAC + site scope) or OpenID Connect (Keycloak) |
| Tenancy | one database per organisation (opt-in), the control database holds the identity |

---

## The table of contents

- [The modules](#the-modules)
- [The architecture](#the-architecture)
  - [The three Maven modules](#the-three-maven-modules)
  - [The data model](#the-data-model)
  - [The tenancy](#the-tenancy)
  - [The security](#the-security)
- [Getting started](#getting-started)
- [The configuration](#the-configuration)
- [The operation](#the-operation)
  - [The deployment](#the-deployment)
  - [The release gate](#the-release-gate)
  - [The backup and the restore drill](#the-backup-and-the-restore-drill)
  - [The health](#the-health)
  - [The schedulers](#the-schedulers)
  - [The single sign-on](#the-single-sign-on)
  - [The tenants](#the-tenants)
- [The development](#the-development)
  - [The conventions](#the-conventions)
  - [The test strategy](#the-test-strategy)
  - [The directory map](#the-directory-map)
- [The documentation](#the-documentation)
- [The state and the next steps](#the-state-and-the-next-steps)

---

## The modules

One application, one set of roles, one audit trail. The domains below are not
separate products — they share the master data, the chart of accounts, the
numbering engine and the audit.

| Domain | What it does |
|---|---|
| **Master data & multi-site** | products (UOM, barcode, batch/serial, standard cost), parties (tax id, credit limit, payment terms), warehouses with racks/bins/docks, production lines, work centres; the concurrency-safe numbering engine; the tax SPI with effective-dated rules; CSV import/export with a row-level validation report |
| **Accounting** | hierarchical chart of accounts, fiscal periods (`OPEN`/`CLOSED`/`REOPENED`), the double-entry posting engine (a journal is posted only when it balances, in one transaction, with the period row locked), reversals, tax packs (ID PPN 11 %/2 %/exempt, EU VAT), multi-currency with the functional-currency amounts, trial balance |
| **AR & AP** | customer invoices and supplier bills (each posting a real journal), receipts and payments with the allocation (chosen document first, then the oldest open ones), ageing, the three-way match with a configurable tolerance, bank/cash accounts, statement import and reconciliation |
| **Sales, purchase & stock** | sales and purchase orders (discounts, tax per the country pack, credit-limit check), goods receipts, the stock ledger with a **moving average** cost (an issue that would go negative is refused), bills of material, work orders (explode, release, issue, complete) |
| **Assets & planning** | the asset register with the depreciation rule of the category (straight line, declining balance, units of production), the monthly depreciation run (idempotent per period), the disposal with the gain/loss booked, the maintenance plans, the MRP suggestions (net requirement, safety stock, lead time) |
| **Data exchange** | the canonical BDM, the inbox/outbox engine (`RECEIVED → PARSED → VALIDATED → MAPPED → APPLIED`, idempotency per partner), the adapters: REST/JSON, CSV, **Peppol UBL** (BIS Billing 3.0, the exact EN 16931 rule id is reported) and **EDI** (X12 850/810 with 997, EDIFACT ORDERS/INVOIC with CONTRL), the console, the dead-letter queue, the retry scheduler under a named lock |
| **Offline & sync** | the PWA client (IndexedDB outbox, service worker, offline banner), the sync API (`push`/`changes`/`pull`/`status`), the conflict policy (last writer wins for the low-risk fields, a field-level merge, the server wins for the money and the status), the conflict centre, the online-only policy for the postings |
| **The assistant** | the provider gateway (OpenAI-compatible, Anthropic, a local Echo provider) with timeouts, retries and a circuit breaker, the per-organisation configuration (the external providers are **off** by default), the PII redaction before anything leaves the box, the read tools inline and the write tool as a **proposal with a diff** that a human confirms, the insight rules (negative stock, below safety stock, overdue AR) and the RAG over the knowledge vault with citations |
| **HRM** | the employee master with the org structure, the contracts, the leave with the working-day computation and the annual balance, the timesheets (≤ 24 h, never in the future, unique per employee/day/project), the expense claims, the H&S incident register, the payroll run with the cost-centre allocation and the CSV export, the GDPR export and the anonymising erasure (the books never change) |
| **Projects** | projects with the budget and the billing rate, the WBS with the budgeted hours, the milestones (an invoiced milestone **posts a real journal**), the resource assignments with the capacity rule, the cost & revenue tracking (approved timesheets → labour cost → project profitability) |
| **CRM** | the leads with the source/territory/campaign, the qualification and the conversion into the customer of the master data, the pipeline (it never moves backwards, a lost deal needs a reason), the hand-off to Sales (an opportunity becomes a real draft sales order), the activities, the campaigns |
| **Dashboard** | the role-aware KPI tiles (every tile carries the permissions that make it visible and has a drill-through link), the thresholds with the alerts, the saved views, the scheduled reports (CSV, print-ready HTML or **native PDF**) with the subscription per user, the insight board |
| **Knowledge Vault** | the documents with versions (a new version re-indexes the RAG corpus), the folders, the tags, the FULLTEXT search, the legal hold, the retention policies (archive or delete after N months, per folder or organisation) |
| **Helpdesk** | the queues with their SLA tiers (the deadlines are computed by the **database clock**), the intake from the portal/e-mail/phone, the priority read from the words of the requester, the enforced status flow, the public replies and the internal notes, the escalation (manual and by the SLA monitor), the knowledge suggestions, the customer portal (`/portal/{token}` — the token is the secret, no account needed) |
| **Reports & e-mail** | the native PDF reports (Flying Saucer + OpenPDF, headless), the delivery per organisation via SMTP or an API provider (Mailchimp Transactional, SendGrid, Mailgun, a generic webhook), the transport configured **by the administrator in the interface** with the secrets sealed (AES-GCM) |
| **Identity & tenancy** | the local login with the roles, the permissions and the site scope; the OpenID Connect login with the automatic provisioning, the role mapping and the OIDC logout (the form login stays the fallback); one database per organisation with the registry, the routing connection and the provisioning |

---

## The architecture

### The three Maven modules

```
ultibiz-spi/     the stable extension contracts (the plugin descriptor, the menu
                 contributions, the tax rule provider, the FX rate provider) —
                 no framework dependencies
ultibiz-core/    the domain records, the jOOQ repositories, the application
                 services, the Flyway migrations, the numbering engine, the
                 exchange/AI/sync/mail/tenant engines
ultibiz-web/     the Spring MVC controllers, the Thymeleaf views, the security,
                 the i18n (en + id) — packaged as a WAR for Tomcat 11
```

The rule of the layering: the **web layer never touches the database** — it
calls an application service, which owns the transaction and the audit. The
repository layer enforces the site scope (`SiteScopedSql`), so a user scoped to
site A can never read or write site B rows — never the UI.

### The data model

* Every DDL change is a **new Flyway script**; the scripts are self-contained
  (Flyway runs each one on its own connection).
* Master data is soft-deleted (`deleted_at`) and version-checked
  (`row_version`).
* Money and quantities are `DECIMAL`/`BigDecimal` — never floats; the instants
  are UTC with an explicit zone at the edges.
* Every write is audited (`audit_event`); the audit carries the correlation id
  of the request and, for the assistant, the id of the AI conversation.
* The numbering engine (per document type + scope, prefix, padding, monthly or
  fiscal-year restart) is concurrency-safe: 200 parallel callers produce
  contiguous, duplicate-free numbers.

### The tenancy

The **control database** holds the identity (the organisations, the users, the
roles, the site scopes) and the **tenant registry**; every tenant owns a
database with its own business data.

* A filter puts the tenant of the logged-in user on the thread, a routing data
  source gives the connection, and the thread is always cleaned up.
* The mode `single` (the default) changes nothing for an existing installation;
  the mode `database` activates the routing.
* The provisioning creates the database, runs the migrations on it, removes the
  demo data, copies the identity and the reference data and registers the
  tenant. A suspended tenant falls back to the control database.
* The test provisions a **real** second database and proves that a row written
  in the tenant is not in the control database.

Details: [`docs/MULTI-TENANT.md`](docs/MULTI-TENANT.md).

### The security

* Spring Security with the RBAC: the roles carry the permission codes, the
  users carry the site scope; the sessions live in the database (the login
  survives a redeploy).
* The rate limit on the public endpoints (the customer portal, the inbound
  webhook): per IP and path, `429` with `Retry-After`.
* The secrets are sealed with AES-GCM under the deployment key — the API keys of
  the assistant, the SMTP password, the API key of the e-mail provider and the
  secret of the webhook are never stored in clear text and never sent back to
  the browser.
* The single sign-on is optional: the switch is in the interface (Administration
  → System settings) or in the profile `sso`; without it the form login behaves
  exactly as before.

Details: [`docs/SECURITY.md`](docs/SECURITY.md) · [`docs/SSO.md`](docs/SSO.md).

---

## Getting started

**Requirements**: JDK 21, Maven 3.9+, MariaDB 11.8 (or newer).

```bash
# the build with the unit tests and the WAR
mvn -B verify

# the integration tests against a real MariaDB (opt-in: they need a database)
mvn -B verify \
  -Dultibiz.it.jdbcUrl=jdbc:mariadb://localhost:3306/ultibiz_test \
  -Dultibiz.it.user=ultibiz -Dultibiz.it.password=... \
  -Dultibiz.it.adminUser=root -Dultibiz.it.adminPassword=...
```

The integration tests prepare the database themselves (Flyway `clean` +
`migrate`), so the schema under test is always the migration scripts. The
tenant test creates a real second database — it needs the administrator
credentials and is skipped without them.

**The first start**: copy the environment file, fill in the database and the
administrator password, start the service. The `.env` is read by the systemd
unit (`EnvironmentFile=`) and is never part of the repository.

```bash
cp .env.example .env && chmod 600 .env   # the template is in the repository
```

---

## The configuration

**Every item is editable in the interface: Administration → System settings**
(`/admin/settings/system`, the administrators only). The environment variable
stays the fallback — a value changed in the interface wins until it is removed,
and a value that is read at the start of the application takes effect after a
restart (the table marks those items).

The 34 items below are the whole configuration of the deployment. The table is
generated from the registry of the application (`SettingDefinitions`), so it
cannot drift away from the code.

<!-- begin: the configuration table -->
| The group | The item | The environment variable | The default | The effect |
|---|---|---|---|---|
| The database | The database host (`db.host`) | `ULTIBIZ_DB_HOST` | `localhost` | after a restart |
| The database | The database port (`db.port`) | `ULTIBIZ_DB_PORT` | `3306` | after a restart |
| The database | The database name (`db.name`) | `ULTIBIZ_DB_NAME` | `ultibiz` | after a restart |
| The database | The database user (`db.user`) | `ULTIBIZ_DB_USER` | `ultibiz` | after a restart |
| The database | The database password *(sealed)* (`db.password`) | `ULTIBIZ_DB_PASSWORD` | `-` | after a restart |
| The database | The administrator of the database (`db.admin.user`) | `ULTIBIZ_DB_ADMIN_USER` | `root` | after a restart |
| The database | The password of the database administrator *(sealed)* (`db.admin.password`) | `ULTIBIZ_DB_ADMIN_PASSWORD` | `-` | after a restart |
| The security | The password of the first administrator *(sealed)* (`admin.password`) | `ULTIBIZ_ADMIN_PASSWORD` | `-` | after a restart |
| The security | The key that seals the secrets *(sealed)* (`ai.encryption.key`) | `ULTIBIZ_AI_ENCRYPTION_KEY` | `-` | after a restart |
| The tenancy | The mode of the tenancy (`tenant.mode`) | `ULTIBIZ_TENANT_MODE` | `single` | after a restart |
| The single sign-on | The single sign-on is active (`sso.enabled`) | `ULTIBIZ_SSO_ENABLED` | `false` | after a restart |
| The single sign-on | The issuer of the identity provider (`sso.issuer`) | `ULTIBIZ_SSO_ISSUER` | `-` | after a restart |
| The single sign-on | The client id (`sso.client.id`) | `ULTIBIZ_SSO_CLIENT_ID` | `ultibiz` | after a restart |
| The single sign-on | The client secret *(sealed)* (`sso.client.secret`) | `ULTIBIZ_SSO_CLIENT_SECRET` | `-` | after a restart |
| The single sign-on | The redirect URI (`sso.redirect.uri`) | `ULTIBIZ_SSO_REDIRECT_URI` | `-` | after a restart |
| The single sign-on | The automatic provisioning (`sso.auto.provision`) | `ULTIBIZ_SSO_AUTO_PROVISION` | `true` | at runtime |
| The single sign-on | The organisation of a new user (`sso.organization`) | `ULTIBIZ_SSO_ORGANIZATION` | `ACME` | at runtime |
| The single sign-on | The role of a new user (`sso.role`) | `ULTIBIZ_SSO_ROLE` | `USER` | at runtime |
| The single sign-on | The administrator roles of the provider (`sso.admin.roles`) | `ULTIBIZ_SSO_ADMIN_ROLES` | `ultibiz-admin,admin` | at runtime |
| The e-mail (the fallback of the environment) | The transport (`email.provider`) | `ULTIBIZ_EMAIL_PROVIDER` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The SMTP host (`email.smtp.host`) | `ULTIBIZ_SMTP_HOST` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The SMTP port (`email.smtp.port`) | `ULTIBIZ_SMTP_PORT` | `587` | at runtime |
| The e-mail (the fallback of the environment) | The SMTP user (`email.smtp.user`) | `ULTIBIZ_SMTP_USER` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The SMTP password *(sealed)* (`email.smtp.password`) | `ULTIBIZ_SMTP_PASSWORD` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The sender address (`email.from`) | `ULTIBIZ_SMTP_FROM` | `ultibiz@localhost` | at runtime |
| The e-mail (the fallback of the environment) | The domain of Mailgun (`email.mailgun.domain`) | `ULTIBIZ_MAILGUN_DOMAIN` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The region of Mailgun (`email.mailgun.region`) | `ULTIBIZ_MAILGUN_REGION` | `us` | at runtime |
| The e-mail (the fallback of the environment) | The URL of the e-mail webhook (`email.webhook.url`) | `ULTIBIZ_EMAIL_WEBHOOK_URL` | `-` | at runtime |
| The e-mail (the fallback of the environment) | The secret of the e-mail webhook *(sealed)* (`email.webhook.secret`) | `ULTIBIZ_EMAIL_WEBHOOK_SECRET` | `-` | at runtime |
| The security | The shared secret of the exchange *(sealed)* (`exchange.webhook.secret`) | `ULTIBIZ_EXCHANGE_WEBHOOK_SECRET` | `-` | at runtime |
| The security | The requests of the rate limit (`rate.limit.requests`) | `ULTIBIZ_RATE_LIMIT_REQUESTS` | `60` | at runtime |
| The security | The window of the rate limit (seconds) (`rate.limit.window`) | `ULTIBIZ_RATE_LIMIT_WINDOW` | `60` | at runtime |
| The reports | The directory of the reports (`report.dir`) | `ULTIBIZ_REPORT_DIR` | `reports` | at runtime |
| The reports | The delivery webhook (`dashboard.delivery.webhook`) | `ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK` | `-` | at runtime |
<!-- end: the configuration table -->

The secrets (the passwords, the API keys, the secrets of the webhooks) are
sealed with AES-GCM under the deployment key. They are **never** stored in
clear text, never shown again and never sent back to the browser: the form only
says whether a secret is set, an empty field keeps it and a ticked box removes
it. When the sealing key is changed, the stored secrets are sealed again with
the new key.

The e-mail transport, the sender address and the credentials of an organisation
stay in **Administration → E-mail settings** (`/admin/settings/email`) — the
settings of the organisation win over the fallback of the deployment.

Three values of the environment file belong to the tools and not to the
application, so they are not in the table: the root account of MariaDB (the
backup and the restore drill), the administrator of the identity provider and
the password of the demo user of the end-to-end test.

The template of the environment file is in the repository (`.env.example`); the
file itself is server-side only and is never committed.
---

## The operation

Full operational notes: [`docs/RUNBOOK.md`](docs/RUNBOOK.md) ·
[`docs/OPERATIONS.md`](docs/OPERATIONS.md).

### The deployment

One ssh invocation: backup the WAR, replace it, delete the exploded directory
(a failed startup has to be able to roll back), restart, read the health.

```bash
cd /home/janedoe/projects/ultibiz
JAVA_HOME=/opt/jdk-21 MAVEN_OPTS="-Xmx900m" bash tools/release-check.sh
cp /opt/tomcat/webapps/ROOT.war /opt/tomcat/ROOT.war.bak
cp ultibiz-web/target/ultibiz-web-*.war /opt/tomcat/webapps/ROOT.war
rm -rf /opt/tomcat/webapps/ROOT
systemctl restart ultibiz-tomcat
curl -s http://localhost:8080/actuator/health        # {"status":"UP"}
```

**Never** sync the tree without `--exclude '.env'` — a `rsync --delete` without
it removes the secrets of the service.

### The release gate

`tools/release-check.sh` runs the i18n audit, the check that this README still
matches the configuration registry, the build with all the tests and the
migration check. The same gate runs in CI (`.github/workflows/release-check.yml`)
on every push with a MariaDB service container.

### The backup and the restore drill

`tools/backup.sh` writes a consistent dump (`--single-transaction`) plus the
report files; `tools/restore-drill.sh` restores it into a scratch database and
compares the row counts table by table — the drill is green on the deployed
instance. Both run as root with the MariaDB root account.

### The health

`/actuator/health` is public and shows the status; the **details** (the name of
the failed indicator) are only for the administrators. `/actuator/health/liveness`
and `/actuator/health/readiness` are the probes.

### The schedulers

The report scheduler (daily/weekly/monthly), the insight engine, the retention
run of the knowledge vault, the SLA monitor of the helpdesk and the retry batch
of the exchange — every one of them runs under a MariaDB named lock, so two
replicas never do the same work twice.

### The single sign-on

The provider is a container on the deploy host:

```bash
bash deploy/keycloak/start-keycloak.sh          # start (generates the secret)
bash deploy/keycloak/start-keycloak.sh status
bash deploy/keycloak/start-keycloak.sh stop
```

It listens on `localhost:8180`. For real users the provider has to be reachable
over **https** (Keycloak 26 marks the session cookie as *secure*), so the
reverse proxy has to forward a public hostname to it — that forwarding lives
outside this repository. The end-to-end test drives the whole flow:
`python3 tools/sso-smoke-test.py`.

### The tenants

**Administration → Tenants** (`/admin/settings/tenants`, the administrators
only): the list of the tenants, the provisioning and the suspension. The mode
stays `single` until the provisioning, the isolation and the rollback path are
verified — then `ULTIBIZ_TENANT_MODE=database` activates the routing.

---

## The development

### The conventions

* **After every completed change: update this README and push to GitHub.**
* The authoring prompts are internal working documents and are deliberately
  **not** published.
* No secret ever enters the repository; `.env` is server-side only.
* Every DDL change is a new Flyway script; every write is audited.
* The site scoping is enforced in the repository layer — never in the UI.
* The i18n: every key exists in `messages.properties` **and**
  `messages_id.properties`; `tools/i18n-audit.sh` must report PASS.
* The configuration table in this README is **generated** from the registry:
  after adding an item to `SettingDefinitions`, run
  `python3 tools/readme-config-table.py`. The release gate runs it with
  `--check`, so the documentation cannot drift away from the code.

### The test strategy

* **Unit tests** for the domain, the policy and the engine rules.
* **Integration tests** against a real MariaDB for the repository and the
  posting engine — the database is prepared by Flyway itself.
* **Web tests**: a slice test for the controllers and a **full context test**
  (`ApplicationContextTest`) that boots the whole application against the test
  database — a slice test does not load the domain layer and would not catch a
  broken wiring.

### The directory map

```
ultibiz-web/src/main/resources/
  templates/            the Thymeleaf views (the fragments/layout.html shell)
  static/css|js/        app.css, theme.js, app.js
  static/vendor/        the vendored Bootstrap 5.3.3 + htmx 2.0.4 (+ SHA256SUMS)
  messages*.properties  the i18n (en default, id)
ultibiz-core/src/main/resources/db/migration/
  V202611250001__ultibiz_baseline.sql   the organisation, the site, the user,
                                         the RBAC, the audit, Spring Session
  V202611250018__system_settings.sql    the configuration of the deployment,
                                         edited by the administrator
  …                                     one script per slice, in order
deploy/                 the Dockerfile, the compose file, the k8s manifests,
                        the keycloak realm + the start script
tools/                  install-server1.sh, dev-up.sh, release-check.sh,
                        i18n-audit.sh, backup.sh, restore.sh, restore-drill.sh,
                        sso-smoke-test.py, readme-config-table.py, vendor-assets.sh,
                        qa-e2e.py, qa-static-audit.py, qa-instance-setup.sh
docs/                   the guides and the ADRs
```

---

## The documentation

| Document | Content |
|---|---|
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | the installation, the services, the deploy, the rollback |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | the day-to-day: the deploy, the backup, the schedulers, the secrets, the troubleshooting |
| [`docs/SECURITY.md`](docs/SECURITY.md) | what protects the application and where the limits are |
| [`docs/SSO.md`](docs/SSO.md) | the single sign-on: the flow, the provisioning, the provider |
| [`docs/MULTI-TENANT.md`](docs/MULTI-TENANT.md) | one database per organisation: the control database, the routing, the provisioning |
| [`docs/MASTER-DATA.md`](docs/MASTER-DATA.md) | the master data, the multi-site, the numbering |
| [`docs/ACCOUNTING.md`](docs/ACCOUNTING.md) | the posting engine, the periods, the taxes, the currency |
| [`docs/RELEASE-NOTES-PLUGINS.md`](docs/RELEASE-NOTES-PLUGINS.md) · [`docs/RELEASE-NOTES-SSO-TENANCY.md`](docs/RELEASE-NOTES-SSO-TENANCY.md) | what the plugins, the SSO and the multi-tenant brought |
| [`docs/QUALITY-ASSURANCE.md`](docs/QUALITY-ASSURANCE.md) | the quality assurance: the three layers, the tools, the quality-assurance instance, the defects that were found |
| [`docs/adr/`](docs/adr/) | ADR-001 (the decisions of the foundation), ADR-002 (the scoping and the numbering), ADR-003 (the posting engine) — read them before changing the rules |

## The state and the next steps

Everything below is implemented, deployed, verified and pushed to GitHub: the master data and the multi-site, the accounting with
the tax packs and the currency, the AR/AP with the bank reconciliation, the
orders/stock/manufacturing, the assets and the MRP, the data exchange with
Peppol and EDI, the offline sync, the assistant, the six plugins (HRM, projects,
CRM, dashboard, knowledge vault, helpdesk), the hardening and the release
tooling, the PDF reports and the e-mail, the e-mail settings in the interface,
the single sign-on and the multi-tenant on the database level.

The open items, honestly:

* **the public URL of the identity provider** — the proxy has to forward a
  hostname to the Keycloak container and the provider has to speak https; until
  then the SSO stays switched off and the form login is used;
* **the mode `database` of the tenants** — it stays `single` until the
  provisioning, the isolation and the rollback path are drilled on a live
  instance;
* **the known gaps**: the stock transfer and the cycle count, the routing and
  the capacity planning, the back-flushing, the lot/serial traceability, the
  year-end closing and the carry-forward, the approval thresholds, the
  detail forms for the parties and the warehouses;
* **the native vector index** of the RAG — MariaDB 11.8 ships the `VECTOR`
  type but not the vector functions, so the retrieval runs on the text copy of
  the embedding and the fulltext index (portable, a little slower).
