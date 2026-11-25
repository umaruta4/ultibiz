# UltiBiz — Phase Prompts (copy/paste per phase)

> Companion to `ULTIBIZ_MASTER_PROMPT.md` (the spec). Each block below is a self-contained
> prompt to paste into a coding agent for one phase. Always paste the **Ground Rules** header
> with it, and point the agent at the master prompt file in the repo.

---

## Ground Rules header (paste with EVERY phase prompt)

```
Read `docs/ULTIBIZ_MASTER_PROMPT.md` in full before you write any code — it is the authoritative
spec for UltiBiz (stack, layering, plugin rules, money/time handling, audit, i18n, offline
policy, reporting format). Do not contradict it.

Hard constraints for this session:
- Java 21, Spring Boot 4.2.x, WAR on Apache Tomcat 11, MariaDB 12.x (min 11.8), jOOQ 3.21,
  Flyway 13, Thymeleaf + HTMX + Bootstrap 5.3 (vendored). No Hibernate, no Spring Data JPA,
  no Spring Cloud, no Kafka/Redis/Elasticsearch, no Node/npm/bundler.
- Plugins are isolated: own tables `ub_<plugin_id>_*`, own Flyway migrations, own i18n, own
  menus. Cross-plugin access only via published SPI/events.
- Money = DECIMAL/BigDecimal with explicit scale + RoundingMode. Time = Instant/LocalDate with
  explicit ZoneId. No floats for money/quantity.
- Every write is audited; every DDL change is a new Flyway script using expand/contract.
- Every user-visible string goes into i18n bundles (en + id).
- No fabrication: run the build/tests and paste real output. If blocked, report the blocker and
  give ≥3 concrete alternatives — never silently downgrade.
- Finish with the report format in §18 of the master prompt (timestamp WIB, Changed, Verified,
  Open).
```

---

## P0 — Foundation

```
Task: build Phase P0 (Foundation) of UltiBiz.

Deliver:
1. Maven multi-module skeleton: parent POM, ultibiz-spi, ultibiz-core, ultibiz-web.
   - Parent: dependencyManagement (BOM: spring-boot 4.2.x), Java 21 release, UTF-8, reproducible
     builds, `maven-enforcer-plugin` (Java + Maven version), surefire/failsafe, jacoco optional.
   - ultibiz-spi: versioned extension contracts only — no Spring imports, no infra types.
2. ultibiz-core: org/site/tenant skeleton.
   - Flyway baseline migrations: organization, site, country, app_user, role, user_role,
     user_site_scope, permission, role_permission, audit_log, session, schema_version metadata.
   - Domain classes in `domain/`, jOOQ repositories in `infrastructure/`, use cases in
     `application/`. No field injection; constructor injection; records for DTOs.
   - Site scoping: `SiteScope` context + repository filter that injects org_id/site_id into
     every query; write a unit test proving a scoped user cannot read another site's rows.
3. Security: Spring Security 7, form login, DB-backed sessions (Spring Session JDBC), CSRF,
   BCrypt/Argon2 password hashing, role→permission evaluator, `403` vs `401` semantics,
   one seeded admin user via a demo seeder (password from env, never hard-coded).
4. Web shell: Thymeleaf layout with Bootstrap 5.3 (vendored via tools/vendor-assets.sh with
   SHA-256 pinning), HTMX 2 vendored, dark-mode toggle (data-bs-theme + localStorage + user
   profile field, prefers-color-scheme default), responsive navbar + sidebar, i18n via
   messages.properties (en) and messages_id.properties (id), error page, health endpoint.
5. Packaging & runtime: WAR with `spring-boot-starter-tomcat` at provided scope,
   `SpringBootServletInitializer`; `tools/dev-up.sh` (docker compose: MariaDB 12 + Tomcat 11 with
   the WAR mounted), `docker-compose.yml`, `tools/vendor-assets.sh`, `tools/seed-demo.sh`.
6. Ops: Actuator + Micrometer/Prometheus, structured JSON logging with correlation id filter,
   Dockerfile (non-root, tomcat:11-jre base), `deploy/k8s/` minimal manifests (web deployment,
   service, configmap, probes), Grafana/Prometheus scrape config sketch.
7. CI: GitHub Actions workflow — build, unit tests, Testcontainers (MariaDB) IT, package WAR,
   upload artifact.

Acceptance gates (all must pass and be shown with real output):
- `mvn -B verify` → BUILD SUCCESS.
- WAR deploys on Tomcat 11 and the login page renders in dark and light mode.
- `curl -s localhost:8080/ultibiz/actuator/health` → {"status":"UP"}.
- Memory: with -Xmx320m the running app stays ≤ 350 MB RSS (measure and paste the number).
- No Node/npm anywhere in the build (grep the tree and paste the result).
```

---

## P1 — Master data, multi-site, numbering, i18n, country/tax SPI

```
Task: build Phase P1 of UltiBiz (read docs/ULTIBIZ_MASTER_PROMPT.md §5, §12 first).

Deliver:
1. Master-data domain: Product (with UOM, barcode, product_type, batch/serial flags, costing),
   Party (customer/vendor/both, tax ids, addresses, contacts, credit limit, payment terms),
   Warehouse, Rack, Bin/StorageLocation, Dock (SUPPLY/RECEIVING/DELIVERY/DISPATCH),
   ProductionLine, WorkCenter/Machine, Calendar, UOM conversion table.
   Each with jOOQ repository, Flyway migrations, audit columns, row_version, soft delete.
2. Multi-site: every transactional table carries site_id; SiteScope enforced in repositories;
   integration tests that a user scoped to site A cannot read/write site B, and that
   org isolation holds across two orgs in one schema.
3. Numbering engine: per document_type + scope sequences, prefix, padding, restart on period,
   concurrency-safe (row lock / atomic update), and statutory per-country formats.
4. Country configuration: country table + per-org enabling; currency, locale, timezone, fiscal
   calendar; tax SPI skeleton (`TaxRuleProvider`) with effective-dated rates and categories,
   snapshotting the rule used on each computed tax line.
5. i18n pipeline: message bundles per module, parameter interpolation, plural support,
   Accept-Language negotiation, per-user override, admin translation upload endpoint,
   missing-key audit command (`mvn -q -pl ultibiz-core tools:i18n-audit` or a small script).
6. CSV import/export for product & party master with a validation report (rows, errors,
   downloadable error CSV) and dry-run mode.
7. UI: master-data list/detail screens — searchable, filterable, page size, column chooser,
   saved filters per user, export CSV, print view; responsive and dark-mode correct.

Gates: mvn -B verify green; scoping ITs green; numbering concurrency test green (200 parallel
requests → zero gaps, zero duplicates); i18n audit reports zero missing keys for en and id.
```

---

## P2 — Accounting

```
Task: build Phase P2 of UltiBiz (Accounting plugin) per master prompt §8.1 and §12.

Deliver:
1. Chart of accounts (org-scoped, country-aware, hierarchical, active/inactive), journal types,
   fiscal periods with open/close/soft-close (reopen requires permission + audit), and
   year-end closing + carry-forward.
2. Double-entry postings: vouchers with lines (debit/credit), balanced by construction,
   posting engine that writes journal entries + line rows in ONE transaction; posted documents
   are immutable — corrections are reversals. Never edit a posted journal.
3. AR: invoices, credit notes, receipts, allocations, aging report, dunning rules.
   AP: bills, payments, 3-way match (PO/receipt/bill) with tolerance config.
4. Tax engine: effective-dated rates/categories, inclusive & exclusive pricing, line-level vs
   total-level rounding with per-country RoundingMode, rule snapshot stored on each tax line.
   Ship two country tax packs (e.g. Indonesia PPN + a generic EU VAT pack) behind the SPI.
5. Multi-currency: transaction currency, functional currency, FX rate table + RateProvider SPI,
   revaluation at period close with gain/loss postings, rounding at document and total level.
6. Bank/cash: accounts, statements (CSV import), reconciliation with match tolerance.
7. Audit export: journals and postings exportable with full audit trail and before/after diff.
8. UI: CoA tree, journal entry editor with live balance check, period calendar, AR/AP ageing,
   tax summary, reconciliation screen; role/permission gates for posting; approval thresholds.

Gates: mvn -B verify green; property test: random journals always balance (debits == credits) and
never post unbalanced; posting reversal restores balances exactly; tax pack fixtures match
expected amounts for the two countries (show the fixture table in the report); AR aging and
3-way-match scenarios behave per spec.
```

---

## P3 — Sales & Order Management

```
Task: build Phase P3 of UltiBiz (Sales + Order Management plugins).

Deliver:
1. Price lists, customer price agreements, quantity/volume tiers, discount policy with
   approval thresholds, effective dating.
2. Quotation → sales order conversion; sales orders with allocation, backorder, releases,
   shipments (picking lists, partial shipments), status state machine (documented transitions).
3. Purchase orders → goods receipts (into warehouse bins, with dock reference) → purchase bills
   (hand-off to Accounting), supplier price lists, PO change/cancellation.
4. Order-to-cash and procure-to-pay traces: order → shipment → invoice → ledger posting is
   consistent and idempotent; a repeated webhook/EDI delivery does not create a second invoice.
5. Order import/export CSV and REST endpoints for external channels.
6. UI: quote builder, order workbench (board by status), allocation view, shipment/pick UI,
   receipt UI (dock + bin target), approval inbox, exception queue (shortages, overdue).

Gates: mvn -B verify green; end-to-end IT: quote→order→ship→invoice→ledger produces balanced
postings; duplicate-delivery test proves exactly one invoice; approval threshold blocks and
routes correctly.
```

---

## P4 — Manufacturing & Asset Management

```
Task: build Phase P4 of UltiBiz (Manufacturing + Asset Management plugins).

Deliver:
1. Manufacturing: items & routings, multi-level BOM with engineering change control (ECN),
   work orders (planned/issued/released), production line assignment, capacity + shift
   calendar, material issue/return, WIP, standard vs actual costing, basic MRP (net
   requirements with lead times, safety stock, on-hand projection), lot/serial traceability.
2. Production reporting: output receipt to a bin, scrap, downtime reason capture.
3. Assets: register, categories, capitalization from purchase (Accounting hand-off),
   depreciation (straight-line, declining balance, units-of-production) with schedules and
   postings to Accounting, disposal, transfer between sites, maintenance plans + work orders,
   meter readings, downtime link to Manufacturing.
4. UI: BOM tree browser, work order board (kanban per line), MRP suggestion screen with
   one-click order creation, asset register with depreciation preview, maintenance calendar.

Gates: mvn -B verify green; BOM explosion correctness test (multi-level, phantom items);
WO→output receipt→inventory→GL reconciliation test; depreciation schedule matches fixture for
each method; MRP suggestion test with known on-hand/lead-time data.
```

---

## P5 — Data Exchange (EDI, Peppol UBL, REST/JSON)

```
Task: build Phase P5 of UltiBiz (Data Exchange plugin) per master prompt §7.

Deliver:
1. Canonical BDM (document types: invoice, credit note, order, despatch advice, purchase
   invoice, statement; parties with electronic addresses; lines; tax breakdown; totals;
   payment terms; references; attachments) + mapping API.
2. Inbox/outbox: raw payload storage, idempotency keys, status machine
   RECEIVED→PARSED→VALIDATED→MAPPED→APPLIED/REJECTED/FAILED, retry with backoff,
   dead-letter queue, operator replay, per-document error report.
3. Adapters:
   a. REST/JSON: inbound webhook + outbound client (OAuth2 client-credentials, mTLS),
      JSON-Schema validation, OpenAPI 3.1 descriptors.
   b. Peppol UBL: outbound Invoice/Credit Note in UBL 2.1 per Peppol BIS Billing 3.0
      (EN 16931), correct cbc:EndpointID + schemeID, correct VAT breakdown (UNCL5305 /
      UNCL5168), configurable CustomizationID/ProfileID; validation chain = UBL 2.1 XSD →
      EN 16931 Schematron (Saxon HE, XSLT 2.0) → Peppol Schematron incl. country rules; only
      fatal rules reject, warnings stored on the document. Inbound parse+validate+map to a DRAFT
      invoice (never auto-posted without policy).
      Ship `peppol-sim` (local simulator accepting/returning messages, used in CI).
   c. EDI: X12 (850/810/856/812/832 subset) and EDIFACT (ORDERS/INVOIC/DESADV) — hand-written
      interchange parser (ISA/GS/GE/IEA envelope, segment map), partner-specific mapping config,
      automatic 997 / CONTRL acknowledgement.
   d. CSV/Excel flat files (CSV first) for master data and bank statements.
4. Mapping console UI (visual-ish JSON mapping editor with test-run), partner registry
   (protocol, endpoint, credentials, certs), document browser with payload/validation/errors,
   monitoring dashboard (throughput, failures, DLQ).
5. Scheduled/automated jobs are DB-locked and k8s-safe (no double delivery with 2+ replicas).

Gates: mvn -B verify green; golden-file round-trip tests for every adapter; duplicate-delivery
test (same idempotency key, twice) → exactly one posted document; a deliberately invalid Peppol
invoice is rejected with the exact fatal rule id (e.g. BR-CO-25) and the report shows the
validation output; EDI ack generation verified against a sample envelope.
```

---

## P6 — Offline-First & Sync

```
Task: build Phase P6 of UltiBiz per master prompt §10.

Deliver:
1. Service worker: precache app shell + vendored assets (versioned cache, stale-while-revalidate),
   offline detection, "offline" banner, installable PWA manifest + icons.
2. IndexedDB store (dependency-free, hand-rolled thin wrapper): document snapshots, outbox of
   mutations (clientId, opId, entity, entityId, baseVersion, payload, createdAt, attempts,
   lastError), sync metadata (cursor).
3. Server: ub_change_log (monotonic sequence per org), ub_idempotency table, POST /api/sync/push,
   GET /api/sync/pull?cursor=, GET /api/sync/changes?since=, per-item results
   APPLIED|CONFLICT|REJECTED|DUPLICATE; optimistic concurrency on row_version.
4. Conflict policy engine: LWW for low-risk fields, field-level merge for disjoint draft edits,
   server-wins + review for numeric/financial/status fields. Conflict Center UI with field diff
   and keep-mine / keep-theirs / merge actions.
5. Sync engine: background worker (DB lock, leader-safe) + manual "Sync now", status bar with
   pending/queued/failed, operator-readable failure reasons, retry policy.
6. Offline policy enforcement in UI and server: accounting postings, inventory transfers and
   invoice issuance are online-only; the UI states this when offline. Draft creation, timesheets,
   attendance, stock counts, tickets and notes work offline.
7. Deterministic test harness: two clients, random interleavings, assert convergence + no double
   posting + no lost update on numeric fields.

Gates: mvn -B verify green; convergence harness green over N randomized runs; financial posting
attempted offline is blocked with a clear message; offline draft round-trips through the outbox.
```

---

## P7 — AI Assistant

```
Task: build Phase P7 of UltiBiz per master prompt §11.

Deliver:
1. AiGateway + AiProvider SPI: OpenAiCompatibleProvider (works with OpenAI, llama.cpp, vLLM,
   Ollama, new-api), AnthropicProvider, OllamaProvider, EchoProvider (tests). Per-org config
   (base URL, model, encrypted key, temperature, timeout, external-provider policy). Provider
   outage degrades gracefully with a clear message; every call has a timeout + retry + circuit
   breaker.
2. Assistant UI available on EVERY screen: header button + Ctrl/⌘K command palette + side drawer,
   streaming responses over SSE, context envelope (route, entity, filters — never raw HTML),
   citations for RAG answers, conversation history per user, feedback thumbs.
3. AiToolProvider per module (read tools run immediately + logged; write tools return a PROPOSED
   ACTION with a diff, require human confirmation, then execute through the same application
   service as the UI, with audit records carrying the AI conversation id).
4. Insight engine: scheduled anomaly rules (inventory imbalance, negative stock, overdue AR/AP,
   cost variance, idle capacity, missing master data, unusual entries, expiring contracts, SLA
   breaches) → Alerts in Dashboard/Helpdesk with evidence, confidence and suggested action.
5. RAG: Knowledge Vault chunking + embedding into MariaDB VECTOR(768) with VECTOR INDEX (HNSW),
   hybrid retrieval (vector + FULLTEXT), answers with citations; no-answer handled explicitly.
6. Governance: per-country PII redaction, per-org "allow external providers" toggle (off by
   default in strict jurisdictions), full prompt/response/tool-call audit with token + cost
   tracking, retention policy, admin-visible usage dashboard.

Gates: mvn -B verify green; assistant answers a knowledge question with working citations; a
write tool (e.g. draft_journal) requires confirmation and appears in the audit log with the AI
conversation id; with the provider unreachable the UI shows a graceful error and the rest of the
app is unaffected; RAG returns "not found in the knowledge base" when the corpus has no answer.
```

---

## P8 — HRM, Projects, CRM, Dashboard, Knowledge Vault, Helpdesk

```
Task: build Phase P8 of UltiBiz — six plugins, one per iteration, each with its own acceptance
checklist (do not build them all in one pass).

Deliver, per plugin:
- HRM: employee master, org/position, contracts, attendance & leave, timesheets (feeds Projects),
  payroll export, training/certifications, H&S incident register (feeds Helpdesk), expense claims,
  cost-centre allocation. GDPR-ish personal-data export/erasure hooks.
- Projects: project/WBS, budgets, milestones, timesheet approval, cost & revenue tracking,
  billable/non-billable, resource conflicts, project profitability (feeds Accounting).
- CRM: leads, pipeline/stages, activities/tasks, contacts, campaigns (light), opportunities,
  quote hand-off to Sales, territories.
- Dashboard: role-based KPI tiles, saved views, drill-through, alerts & thresholds, scheduled
  report delivery (email/PDF), insight board consuming AiInsightProvider.
- Knowledge Vault: document store with versioning, folders/tags, MariaDB FULLTEXT search,
  retention policies, RAG corpus (chunk/embed/vector index), citations.
- Helpdesk: tickets, categories/SLA policies, priority, assignment/routing, canned responses,
  KB linking, escalation, customer-facing portal view, satisfaction survey.

Gates: mvn -B verify green after each plugin; each plugin ships: role/permission matrix, unit
tests for its business rules, i18n keys (en+id), responsive dark-mode UI, README, and one
end-to-end IT (e.g. timesheet → project cost → accounting posting).
```

---

## P9 — Hardening & Release

```
Task: build Phase P9 of UltiBiz per master prompt §15, §18, §19.

Deliver:
1. Helm chart + plain k8s manifests: web (2 replicas, RollingUpdate maxUnavailable 0, preStop
   drain, PDB, HPA, probes), worker (leader election via DB lease — no Redis), migrations Job
   (pre-install/pre-upgrade hook), ingress, service, configmap/secret, networkpolicy, SA without
   cluster-admin, prod-grade Dockerfile (pinned digest, non-root, read-only fs, SBOM).
2. Observability: Prometheus scrape + Grafana dashboards (RED, HikariCP, JVM, Tomcat, jOOQ,
   plugin job timers) + alert rules + Loki/ELK-friendly JSON logs with correlation id.
3. Performance: measure and report p95 list render < 400 ms, journal post < 150 ms, 10 000-change
   sync < 60 s, startup-to-ready < 45 s, 200 concurrent users at ≤ 350 MB RSS on 4 vCPU / 8 GB.
   Fix the top offenders; publish before/after numbers.
4. Security: OWASP review pass (CSRF, CSP, headers, session fixation, XSS encoding, SQLi
   parameterisation, file upload scanning, rate limits, secret hygiene, dependency CVEs),
   plus a plugin-permission audit (a plugin cannot act without granted capabilities).
5. Backup/restore: tools/backup.sh, tools/restore.sh, retention, and a CI drill that restores a
   seeded database into a clean MariaDB and runs the smoke suite against it.
6. Upgrade drill: from the previous release to this one, on a copy of production-like data —
   expand/contract respected, zero downtime on parallel Tomcat deployment, documented rollback.
7. Docs: ARCHITECTURE.md, ADR log, SECURITY.md, UPGRADE.md, OPERATIONS.md, API.md (OpenAPI),
   module READMEs — all current.

Gates: mvn -B verify green; load test meets the stated targets with pasted numbers; restore
drill green; upgrade drill shows zero downtime; docs reviewed for staleness; final report follows
§18 format.
```

---

## Quick "one-shot" kickoff prompt (if you just want the agent to start)

```
You are building UltiBiz, a Java ERP. Read `docs/ULTIBIZ_MASTER_PROMPT.md` in full and treat it
as the authoritative spec — stack, layering, plugin isolation, money/time handling, audit, i18n,
offline policy, phase plan, reporting format.

Start with Phase P0 (Foundation) only: Maven multi-module skeleton (spi/core/web), MariaDB
Flyway baseline, org/site/user/role/audit schema, Spring Security 7 + DB sessions, Thymeleaf +
vendored Bootstrap 5.3 shell with dark mode and responsive nav, WAR packaging for Tomcat 11,
Dockerfile + docker compose + dev-up.sh, Actuator health/metrics, and a GitHub Actions CI running
`mvn -B verify`.

Constraints: Java 21, Spring Boot 4.2.x, jOOQ 3.21, Flyway 13, MariaDB 12.x, no Hibernate, no
Spring Data JPA, no Spring Cloud, no Kafka/Redis/Elasticsearch, no Node/npm. RSS ≤ 350 MB.
All DDL via Flyway, all user-visible strings i18n'd (en + id), every write audited, money in
DECIMAL/BigDecimal.

Verify with real command output — run `mvn -B verify`, boot the WAR on Tomcat 11, curl the health
endpoint, and paste the measured RSS. Report: timestamp WIB, Changed, Verified, Open.
```
