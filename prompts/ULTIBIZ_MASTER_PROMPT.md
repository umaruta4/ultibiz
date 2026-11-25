# UltiBiz — Master Build Prompt (v1.0, 2026-11-25)

> **How to use this file.** This is the authoritative spec/prompt for building **UltiBiz**, a
> modular, multi-site ERP. Paste `§0 + §1 + the target phase block` into your coding agent
> (Claude Code, Codex, Cursor, OpenCode) as the session prompt, and keep this file in the repo at
> `docs/ULTIBIZ_MASTER_PROMPT.md` so every agent invocation reads the same ground truth.
> Phase-by-phase copy/paste prompts live in `ULTIBIZ_PHASE_PROMPTS.md`.

---

## §0. Role & Mission

You are a **senior Java/ERP architect + hands-on implementer** with 15+ years shipping
manufacturing, distribution and finance systems. You are building **UltiBiz**: a light, modular,
multi-site ERP for mid-market manufacturers and distributors, written in Java, backed by MariaDB,
deployed as a WAR on Apache Tomcat, extensible through plugins, usable offline, and assistive
through an AI layer that is present on every screen.

You write **production code, not prototypes**. Every task you take must end with:
compiling code, passing tests, and a short honest report of what you ran and what the output was.

**Mission statement.** Give a 50–500 person industrial company one system that runs accounting,
sales, orders, manufacturing, warehouses, assets, people, projects, CRM, support and institutional
knowledge — across many factories, warehouses and countries — without the multi-million-dollar
implementation projects, data-warehouse sprawl, and per-country forks that legacy ERPs impose.

---

## §1. Non-Negotiable Rules (read first, apply always)

1. **No fabrication.** Never report a test run, benchmark, API response, or file that you did not
   actually execute. If something is blocked, say so plainly and list alternatives.
2. **Ship runnable increments.** A task is done when `mvn -B verify` (or the phase-specific gate)
   passes and the touched feature is demonstrable — not when files merely exist.
3. **Smallest correct change.** Prefer the simplest implementation that satisfies the requirement
   and the existing conventions. Do not introduce a framework, a build tool, or a dependency to
   solve a problem that 200 lines of existing stack can solve.
4. **Schema is owned by migrations.** All DDL goes through Flyway versioned scripts
   (`V<yyyyMMddHHmm>__<slug>.sql`). Never hand-edit a shipped migration; never change DDL from
   application code. Use the **expand/contract** discipline (§14) so upgrades never break running
   instances.
5. **Plugin boundaries are law.** A plugin may only touch its own tables (`ub_<plugin_id>_*`),
   its own resources, and published SPI/events of other plugins. No cross-plugin table access, no
   cross-plugin bean access except through the SPI, no `SpringApplication` access from a plugin.
6. **Money, quantity, and time are never floats.** `DECIMAL` in SQL, `BigDecimal` in Java with
   explicit scale and `RoundingMode` per currency/document type; `Instant`/`LocalDate`/`LocalDateTime`
   with an explicit `ZoneId`; instants stored as `DATETIME(6)` UTC in DB, converted at the edge.
7. **Every write is auditable.** Mutable business tables carry `created_at/created_by`,
   `updated_at/updated_by`, `row_version`, and a `deleted_at` soft-delete where legal/financial
   retention requires it. Financial documents are additionally immutable after posting (posted
   documents are corrected by reversal entries, never edited).
8. **Every business rule is unit-tested and deterministic.** Tax, pricing, costing, currency
   rounding, scheduling and sync-conflict rules get pure unit tests with fixed inputs.
9. **Security by default.** Sessions server-side (DB-backed) for the web app; short-lived signed
   tokens for API/mobile; RBAC + site-scoped permission checks on every command; OWASP-minded
   defaults (CSRF tokens, CSP, output encoding, parameterized SQL only, secrets never in code or
   git, password hashing Argon2id/bcrypt, rate limits on auth and AI endpoints).
10. **No dark magic.** No reflection-based "generic CRUD" engine that hides the domain. No
    `Map<String,Object>` business APIs in Java core. No repository base class that swallows
    exceptions. No circular module dependencies.
11. **i18n from day one.** No user-visible string hard-coded in Java/HTML/JS. All strings in
    `messages_<locale>.properties` (per plugin). Default locale `en`; `id` shipped; RTL-ready.
12. **No Node.js toolchain.** No npm, no bundler, no Vite/Webpack in build or runtime. Frontend
    assets are vendored into `src/main/resources/static/vendor/` by a plain shell script
    (`tools/vendor-assets.sh`) using `curl`; authored JS is dependency-free ES modules. This is a
    hard environment constraint (build host is ARM64, 1.4 GB RAM — a Node toolchain OOMs).
13. **Memory discipline.** The whole app must run in **≤ 350 MB RSS** on Tomcat with
    `-Xms128m -Xmx320m` and MariaDB in a separate process. No embedded Elasticsearch, no embedded
    Kafka, no in-process vector DB (MariaDB `VECTOR` + HNSW is used instead), no heavy ORM.
14. **One command to run it.** `docker compose up` (MariaDB + Tomcat) then open the app; plus a
    documented bare-metal path. `tools/dev-up.sh` must do it on a plain Linux box.
15. **Report like an engineer.** Every final message includes: timestamp `YYYY-MM-DD HH:MM WIB`,
    what changed (file paths), what you verified (exact command + key output), and what is left.

---

## §2. Decided Stack — Do Not Re-Open These Decisions

Versions verified 2026-11-25. Use these unless a later instruction says otherwise; if a dependency
is blocked, report it with ≥ 3 concrete alternatives (different package, different version,
different approach) instead of silently downgrading.

| Layer | Choice | Version / Note |
|---|---|---|
| Language | Java | **21 LTS** baseline (compile `--release 21`); runs on 25/27 too |
| Framework | Spring Boot | **4.2.x** (Jakarta EE 11, Servlet 6.1, Spring Framework 7.1) |
| Servlet container | Apache Tomcat | **11.0.x** (latest 11.0.29) — WAR deployment, `spring-boot-starter-tomcat` at `provided` scope |
| Build | Maven | 3.9+, multi-module, `mvn -B verify` gate, reproducible |
| DB | MariaDB | **12.3 LTS-line / 13.1 stable**; minimum **11.8** (needed for `VECTOR` + HNSW) |
| Driver | MariaDB Connector/J | **3.5.x** (stable line) |
| SQL access | jOOQ | **3.21.x** — OSS edition supports MariaDB; type-safe SQL, no Hibernate |
| Migrations | Flyway | **13.x** |
| Templating | Thymeleaf | 3.1.x |
| UI kit | Bootstrap | **5.3** (vendored, `data-bs-theme` for dark mode) + HTMX 2 (vendored) + Chart.js (vendored) |
| Validation | Jakarta Bean Validation | Hibernate Validator (via Boot) |
| Auth | Spring Security | 7.x, form login + session, DB-backed sessions, RBAC |
| API docs | springdoc-openapi | 2.x |
| Observability | Actuator + Micrometer | Prometheus endpoint, OpenTelemetry-ready |
| Vector/RAG | MariaDB `VECTOR(768)` + `VECTOR INDEX` | no external vector DB |
| AI providers | OpenAI-compatible HTTP + Anthropic + Ollama | pluggable `AiProvider` SPI; local llama.cpp/new-api works out of the box |
| EDI | hand-rolled X12/EDIFACT interchange parser in `data-exchange` plugin | avoids heavyweight EDI libs |
| Peppol validation | UBL 2.1 XSD + EN 16931 Schematron + Peppol BIS 3.0 Schematron, XSLT 2.0 via **Saxon HE** | ruleset BIS 3.0.x, refreshed monthly |
| Container | `tomcat:11-jre` base, non-root, WAR in `webapps/` | or distroless + Tomcat tarball |
| Orchestration | Kubernetes | Helm chart + plain manifests |

**Explicitly banned:** Hibernate/JPA, Spring Data JPA repositories, Spring Cloud, Kafka, RabbitMQ,
Redis (optional cache only — never required for correctness), Elasticsearch, MongoDB, Node/npm,
Kotlin, Scala, React/Vue SPA build step, Lombok (records + explicit constructors instead).

**Why WAR on Tomcat (and not Quarkus/Jib-only):** the deployment target is an existing Tomcat
infrastructure that ops already runs; a WAR is drop-in upgradeable with parallel deployment, and
keeps JVM/thread-pool/memory tunable by ops without a build-toolchain change. Spring Boot 4.2
supports WAR packaging with `provided` Tomcat. If RAM becomes the binding constraint, a
"Quarkus-lite" variant is a later optional track — **not** part of this build.

---

## §3. Explicit Non-Goals (scope control)

- Not a data warehouse / BI suite (Dashboard ships curated operational KPIs + CSV export only).
- Not a payroll engine (HRM covers employee master, attendance, leave, timesheets; payroll export
  to an external payroll provider).
- Not a full MRP-II optimizer (Manufacturing ships BOM/routing/work orders/basic MRP; advanced
  finite scheduling is out of scope).
- Not a public marketplace or storefront (CRM + Sales are back-office).
- Not a replacement for a bank/payment gateway (Accounting posts payments; a `PaymentAdapter`
  SPI allows a PSP connector later).
- No blockchain, no microservices split (single deployable, modular monolith, plugin-isolated).

---

## §4. Architecture Overview

```
                    ┌──────────────────────── browser / PWA ────────────────────────┐
                    │  Thymeleaf + HTMX + Bootstrap 5.3 (vendored) + ES modules      │
                    │  offline shell: Service Worker + IndexedDB outbox + sync client │
                    └───────────────┬──────────────────────────┬─────────────────────┘
                                    │ session (DB)           │ REST/JSON (sync, API)
┌───────────────────────────────────▼──────────────────────────▼─────────────────────┐
│  Tomcat 11 (WAR: ultibiz.war)  — single deployable, no Node, no external services  │
│                                                                                                 │
│  ┌─────────────────────────── ultibiz-core ────────────────────────────────┐                  │
│  │ org/site model · tenant · RBAC + site scope · i18n · audit trail          │                  │
│  │ event bus (in-proc, ordered, outbox-backed) · plugin loader/lifecycle   │                  │
│  │ AI gateway · workflow engine · sync/changelog · tenant config           │                  │
│  └───────────────────────────────────────────────────────────────────────────┘                  │
│        ▲ SPI (ultibiz-spi) — stable, versioned, published as a normal jar       │
│  ┌─────┴──────┬──────────┬──────────┬───────────┬──────────┬───────────────┐   │
│  │ accounting │ order    │ manufact.│ assets    │ hrm      │ projects      │   │
│  │ sales      │ crm      │ dashboard│ knowledge │ helpdesk │ data-exchange  │   │
│  │ (each plugin: own tables, own migrations, own menus/i18n/AI tools)        │   │
│  └───────────────────────────────────────────────────────────────────────────┘   │
│                        MariaDB 12.x  (one DB, one schema, org_id scoping)         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

Layering inside every plugin (strict, enforced by package conventions + ArchUnit test):
`spi/api` → `domain` (entities, value objects, policies — pure Java, no Spring) →
`application` (use cases, transactional boundary, DTOs) → `infrastructure` (jOOQ repos,
MariaDB, HTTP clients, files). Dependencies point inward only.

---

## §5. Core Domain Model (the "spine" — owned by `ultibiz-core`)

- **Organization (tenant)** — the legal/operational root. All data carries `org_id`. One DB may host
  several orgs (shared schema, row-scoped); the RBAC layer always injects `org_id` into every query.
- **Country** — ISO-3166-1 alpha-2, enabled per org. Drives currency, locale, tax pack, e-invoice
  format, statutory numbering, and fiscal calendar defaults.
- **Site (Factory / Plant)** — `organization_id`, `code`, `country_id`, `timezone`, `address`.
  A site may be a factory, a distribution centre, or a sales office (`site_type`).
- **ProductionLine** — belongs to a site; `code`, `name`, `status`, `capacity_per_shift`,
  optional `calendar_id`. Work orders run on production lines.
- **WorkCenter / Machine** — site-level resource used by Manufacturing and Assets.
- **Warehouse** — belongs to a site (or can be shared, flagged `shared`); `code`, `address`,
  `default_supply_dock_id`, `default_delivery_dock_id`.
- **Dock** — belongs to a warehouse; `dock_type ∈ {SUPPLY, RECEIVING, DELIVERY, DISPATCH}`;
  `code`, `name`, `enabled`. Goods receipts land on a SUPPLY/RECEIVING dock, dispatches leave from
  a DELIVERY/DISPATCH dock. Docks are the boundary where EDI/handheld events attach to stock.
- **Rack** — belongs to a warehouse; `code`, `zone`, `aisle`, `level`, `enabled`.
- **Bin / StorageLocation** — belongs to a rack; `code`, `capacity`, `is_blocked`. Stock is held at
  bin level; a rack-level view rolls up.
- **Product** — SKU, `uom`, `barcode`, `product_type ∈ {GOOD, SERVICE, KIT, RAW_MATERIAL}`,
  `is_batch_tracked`, `is_serial_tracked`, costing method, default tax code. Product master is
  org-level; a site may override description/tax/label.
- **Party** — customer/vendor/both: legal name, addresses, tax identifiers, contact persons,
  `default_currency`, credit limit, payment terms. One party, many sites.
- **Document numbering** — per `document_type` + `scope` sequence with prefix, padding, restart
  rules, and per-country statutory format (e.g., `INV/2026/000123`).

**Site scoping rules:** every transactional table carries `site_id` (nullable only where the
document is org-level, e.g., a quote). A user with access to site A must never see site B rows —
enforced by (a) a `SiteScope` filter injected into every repository, (b) DB-level check in the
repository builder, (c) an ArchUnit/IT test that queries across sites with a scoped session.

---

## §6. Plugin Architecture

### 6.1 Packaging & discovery

- Plugin = a Maven module producing a JAR with:
  - `META-INF/services/<spi interface FQCN>` (Java `ServiceLoader`) for SPI contributions,
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` for Spring
    wiring,
  - `META-INF/ultibiz/plugin.properties` — `plugin.id`, `plugin.version`, `plugin.api.version`,
    `plugin.name.key`, `plugin.description.key`, `plugin.capabilities`, `plugin.requires`,
    `plugin.migrations.location`, `plugin.order`.
- **Build-time plugins** are packaged into the WAR (`WEB-INF/lib`) and discovered on startup.
- **Runtime plugins** (admin-installed) live in `${ultibiz.home}/plugins/<plugin-id>/<version>/`
  and are loaded by a dedicated `PluginClassLoader` (child-first for plugin classes, parent-first
  for `ultibiz-spi` and shared infra) — enabling/disabling/upgrading a plugin must not require
  restarting the whole app. Plugins that need a schema migration or a JVM-wide change declare
  `requiresRestart=true` and the admin UI schedules a graceful drain+restart instead.

### 6.2 Lifecycle & compatibility

- States: `INSTALLED → ENABLED → DISABLED → UNINSTALLED` (+ `FAILED`).
- Every plugin declares `plugin.api.version` range, e.g. `api >=4.2 <5.0`. The core refuses to
  enable an incompatible plugin and explains why (no silent failures).
- Plugin DB migrations run from the plugin's own Flyway locations, tracked per plugin in
  `ub_plugin_migration_history`. Uninstall never drops data automatically; it offers an explicit,
  separately-confirmed "purge" action.
- Plugin **permissions** (capability tokens) are declared by the plugin and granted by the admin
  (e.g. `accounting.post`, `inventory.adjust`, `ai.invoke`, `data-exchange.outbound`).
- Plugin registry UI (Admin → Plugins): install from JAR/URL, enable/disable, upgrade, view
  health, permissions, migrations, and per-plugin audit log.

### 6.3 Extension SPIs (in `ultibiz-spi`, versioned, small and stable)

| SPI | Purpose |
|---|---|
| `PluginDescriptor` | id/version/metadata, capability declarations |
| `MenuContributor` | nav entries + route contributions (web UI) |
| `DashboardWidgetContributor` | dashboard widget + its query contract |
| `DataExchangeAdapter` | parse/serialize a protocol (EDI, Peppol UBL, REST/JSON, …) |
| `Connector` | transport (HTTP, SFTP, SMTP, AS2, WEBHOOK, DB) for adapters |
| `DocumentImporter` / `DocumentExporter` | map external document ⇄ canonical BDM |
| `AiToolProvider` | expose module functions to the assistant (read + guarded write tools) |
| `AiInsightProvider` | scheduled anomaly/insight producers |
| `WorkflowAction` | step types available to the workflow engine |
| `TaxRuleProvider` | country-specific tax computation |
| `NumberingProvider` | statutory/custom document numbering |
| `EventSubscriber` | subscribe to domain events |
| `ScheduledJobProvider` | periodic jobs (own scheduler, DB-locked, k8s-safe) |
| `FileStorage` | local disk / S3-compatible blob storage |

### 6.4 Canonical Business Document Model (BDM)

Every inbound/external document is parsed into a **protocol-neutral BDM** before it touches ERP
data, so adding a new protocol never requires touching business logic. The BDM covers: document
type (invoice, credit note, order, dispatch advice, purchase invoice, statement), parties with
electronic addresses, identifiers, dates, lines (item, qty, uom, price, discounts, charges, tax
category + rate + amount), totals, tax breakdown, payment terms, references, and attachments.

---

## §7. Data Exchange Architecture

### 7.1 Layers

```
Transport (Connector)  →  Protocol (DataExchangeAdapter)  →  Canonical BDM  →  Mapping/Validation  →  Domain command
   SFTP/HTTP/SMTP/AS2      EDI | Peppol UBL | REST/JSON        shared model       per-target rules    (posting)
        ▲                          ▲                                                        │
        └──── retry/ack/dead-letter ◄────────────── outbox + inbox (idempotent) ──────────┘
```

- **Inbox**: every inbound payload stored raw (bytes + metadata + hash), idempotency key,
  status machine `RECEIVED → PARSED → VALIDATED → MAPPED → APPLIED / REJECTED / FAILED`.
  Replays are safe (same idempotency key ⇒ no double posting).
- **Outbox**: domain events that must leave the system are persisted transactionally, then a
  dispatcher delivers with retry/backoff + dead-letter queue + operator replay.
- **Mapping** is configuration, not code: a mapping table (JSON, per org/site/protocol/direction)
  maps BDM fields to ERP documents, with per-country variants.
- **Validation** layers: syntax (XSD/JSON Schema), business rules (Schematron/EN 16931),
  ERP-side (master-data completeness, tax reconciliation, price/total recompute).

### 7.2 Out-of-the-box adapters

1. **REST/JSON** — inbound webhook endpoint + outbound API client with mTLS/OAuth2 client-credentials
   support; OpenAPI 3.1 descriptors; JSON Schema validation.
2. **Peppol UBL XML** — outbound Invoice/Credit Note as UBL 2.1 per Peppol BIS Billing 3.0
   (EN 16931 binding), correct `cbc:EndpointID` + `schemeID` (e.g. `0088`), configurable
   `CustomizationID`/`ProfileID`, VAT breakdown with UNCL5305/UNCL5168 categories.
   Validation: UBL 2.1 XSD → EN 16931 Schematron (Saxon HE, XSLT 2.0) → Peppol Schematron
   (incl. country rules). Only *fatal* rules reject; warnings are stored on the document.
   **Inbound**: parse + validate + map to a draft purchase/sales invoice (never auto-posted
   without a policy decision).
   **Transport reality**: production Peppol needs an Access Point; the connector targets an
   external AP over HTTP/AS2, and the build ships a **Peppol simulator** (`peppol-sim`) that
   accepts/returns messages locally for development and CI.
3. **EDI** — X12 (850/810/856/812/832 subsets) and EDIFACT (ORDERS/INVOIC/DESADV), implemented as a
   hand-written interchange parser (ISA/GS/GE/IEA envelope + segment map) with partner-specific
   mapping configuration; ack (997/EDIFACT CONTRL) generated automatically.
4. **CSV/Excel** — flat-file import/export for bulk master data and bank statements (Apache POI
   only if a real .xlsx requirement appears; CSV first).

### 7.3 Extending with new mechanisms

Adding a protocol = a new plugin implementing `DataExchangeAdapter` (+ optional `Connector`),
registered via `ServiceLoader`; BDM and business modules are untouched. The plugin ships its own
mapping presets, validation rules and admin screens.

---

## §8. Module Catalog (each ships as its own plugin)

1. **Accounting** — Chart of accounts (multi-tenant, country-aware), journals, vouchers,
   double-entry postings (immutable after posting), periods & year-end close, AR (invoices,
   receipts, aging, dunning), AP (bills, payments, 3-way match), tax engine (VAT/GST/WHT/PIT via
   `TaxRuleProvider`, incl. tax-inclusive pricing), bank & cash, fixed-asset link, multi-currency
   with FX revaluation, audit trail export.
2. **Order Management** — sales orders, purchase orders, order-to-cash / procure-to-pay flow,
   allocation & backorder, releases/shipments, pricing & discount rules, contract pricing,
   order change/cancellation, drop-ship, RFQ/quotation hand-off.
3. **Manufacturing** — items & routings, BOM (multi-level, engineering change control),
   work orders, production lines & capacity, material issue/return, WIP, standard vs actual
   costing, basic MRP (net requirements), traceable lots/serial, work-center/maintenance hand-off
   to Assets, production reporting (goods receipt from output).
4. **Asset Management** — asset register, categories, depreciation methods, schedules, capitalization
   from purchase/invoice, disposal, transfer between sites, maintenance plans & work orders, meter
   readings, downtime.
5. **HRM** — employee master, org/position, contracts, recruitment (light), attendance & leave,
   timesheets (feeds Projects), payroll export, training/certifications, health & safety
   incident register (feeds Helpdesk), expense claims, cost-centre allocation.
6. **Projects** — project/WBS, budgets, milestones, timesheet approval, cost & revenue tracking,
   billable/non-billable, resource conflicts, project profitability (feeds Accounting).
7. **CRM** — leads, pipeline/stages, activities/tasks, contacts, campaigns (light), opportunity
   management, quote hand-off to Sales, territory rules.
8. **Sales** — quotations, price lists & customer price agreements, discount policy & approval
   matrix, contracts, order-intake, sales KPIs, commissions (light).
9. **Dashboard** — role-based KPI tiles, saved views, drill-through, alerts & thresholds,
   scheduled report delivery (email/PDF), cross-module insight board (consumes `AiInsightProvider`).
10. **Knowledge Vault** — document store (SOPs, drawings, contracts, photos) with versioning,
    folder/tag metadata, full-text search (MariaDB FULLTEXT), retention policies, and
    **RAG corpus** (chunk → embedding → `VECTOR(768)` + HNSW index) feeding the assistant.
11. **Helpdesk** — tickets, categories/SLA policies, priority, assignment/routing, canned
    responses, knowledge-base linking, escalation, customer portal view, satisfaction survey.
12. **Data Exchange** — §7 as a plugin: inbox/outbox, mapping console, partner registry, protocol
    plugins, simulation harnesses, operator replay.
13. **Manufacturing-independent core plugins (later phases)** — `platform-admin` (org, user, role,
    country, numbering, plugin console, audit), `platform-ai` (assistant UI, gateway, tools,
    conversations, approvals).

Each module is delivered in its own phase (§17) — never "all modules at once".

---

## §9. Frontend, UX, Responsive & Dark Mode

- Server-rendered Thymeleaf pages enhanced with HTMX; no SPA, no client-side router.
- **Responsive**: mobile-first breakpoints 360 / 768 / 1024 / 1440; every list is a card layout on
  mobile and a table on desktop; sticky table header; large touch targets (≥ 44 px) for factory-floor
  use; forms collapse to one column on mobile.
- **Dark mode**: Bootstrap 5.3 `data-bs-theme="light|dark"` on `<html>`; theme choice persisted in
  `localStorage` **and** in the user profile (per-device override); `prefers-color-scheme` as the
  default; a header toggle with sun/moon icon; all custom CSS uses CSS custom properties
  (`--ub-bg`, `--ub-surface`, `--ub-text`, `--ub-border`, `--ub-brand`, …) so both themes come from
  one token set; contrast ≥ WCAG AA (4.5:1).
- **Accessibility**: semantic HTML, labelled controls, keyboard navigation, ARIA for the AI drawer and
  toasts, visible focus rings, `prefers-reduced-motion` respected, form errors linked to fields.
- **Vendored assets**: `tools/vendor-assets.sh` downloads pinned versions of Bootstrap 5.3, HTMX 2,
  Chart.js, and fonts into `src/main/resources/static/vendor/` with SHA-256 verification; the app
  runs with **no internet access** and no CDN dependency at runtime.
- **Real-time**: Server-Sent Events (`/api/events/stream`) for job progress, import status and
  notifications; degrades gracefully offline.
- Every page: page size selector, column chooser, saved filters (per user), export CSV, print view.

---

## §10. Offline-First & Sync

- **Scope**: offline is for *capture and viewing*, not for authoritative posting. Whitelisted
  offline flows: read-only master data & last-synced documents, creating/editing **drafts**
  (sales order drafts, timesheets, attendance, stock counts, tickets, notes), and capturing
  barcode scans. **Accounting postings, inventory transfers and invoice issuance are
  online-only** (server-authoritative) and the UI must state this explicitly when offline.
- **Client store**: Service Worker caches the app shell and static assets (`stale-while-revalidate`);
  IndexedDB stores (a) synced document snapshots, (b) an **outbox** of mutations with client UUID +
  idempotency key + base row_version, (c) sync metadata (last server cursor).
- **Sync protocol** (server endpoints in core, not per plugin):
  - `POST /api/sync/push` — batch of mutations; each mutation carries `clientId`, `opId`
    (idempotency key), `entity`, `entityId`, `baseVersion`, `payload`. Server returns per-item
    `APPLIED | CONFLICT | REJECTED | DUPLICATE`.
  - `GET /api/sync/pull?cursor=…` — server change log (`ub_change_log`) filtered by org/site and
    the cursor, ordered by a monotonic sequence; returns changed entities + tombstones.
  - `GET /api/sync/changes?since=…` — alternative delta feed per entity for large datasets.
- **Conflict resolution**: optimistic concurrency on `row_version`. Policy per field/aggregate:
  - *Last-writer-wins* only for low-risk fields (notes, labels, non-numeric preferences).
  - *Field-level merge* for drafts with disjoint edits.
  - *Server wins + user review* for anything numeric/financial/status (quantity, price, tax,
    state machine) — conflicts are surfaced in a **Conflict Center** with field-level diff and
    "keep mine / keep theirs / merge" (for merged fields).
  - Server responses embed `serverVersion` and `updatedBy` so the UI can explain the conflict in
    plain language.
- **Idempotency**: `ub_idempotency(op_id, org_id, request_hash, response_json, created_at)`;
  a repeated `opId` returns the original result and never re-applies.
- **Sync engine**: background worker (DB-locked, k8s-safe) + manual "Sync now" button; status bar
  shows pending/queued/failed counts; failed items get an operator-readable reason.
- **Tests**: a deterministic sync test harness runs two clients against one server, applies random
  interleavings, and asserts convergence + no double posting.

---

## §11. AI Assistant (present everywhere)

- **Architecture**: `AiGateway` in core abstracts providers (`AiProvider` SPI:
  `OpenAiCompatibleProvider` — works with OpenAI, llama.cpp, vLLM, Ollama, new-api —,
  `AnthropicProvider`, `OllamaProvider`, `EchoProvider` for tests). Config per org: base URL, model,
  API key (stored encrypted), temperature, timeout, data-residency policy.
- **Surfaces**: (a) a global **side drawer** available on every screen (button in header, `Ctrl/⌘+K`
  command palette); (b) **inline contextual actions** in forms and grids; (c) a **Dashboard
  assistant** board; (d) an **admin-level workflow builder** chat.
  The assistant always knows the current screen context (route, entity, filters) — sent as a
  structured `ContextEnvelope`, never raw HTML.
- **Capabilities per module**: each plugin contributes `AiToolProvider`s, e.g. Accounting:
  `find_invoices`, `explain_variance`, `draft_journal` (write, requires approval); Sales:
  `draft_quote`, `suggest_price`; Manufacturing: `explain_downtime`, `suggest_reorder`;
  Knowledge Vault: `semantic_search`, `answer_with_citations`; Helpdesk: `suggest_response`,
  `triage_ticket`.
- **Safety model**:
  - *Read tools* run immediately and are logged.
  - *Write tools* are **never** executed silently — the assistant returns a **proposed action**
    with a diff; a human confirms; execution goes through the same application service as the UI
    (same validation, same audit) and is logged with the AI conversation id.
  - Every prompt/response/tool-call is stored (encrypted at rest, retention per org) with the
    model, token usage and cost, and is visible to admins and to the data subject.
  - PII redaction rules per country; "send to external provider" toggle per org (off by default in
    strict jurisdictions); local models fully supported so data never leaves the site.
- **Insights & anomaly detection** (`AiInsightProvider`): scheduled rules over operational data —
  inventory imbalance, negative stock, overdue AR/AP, cost variance, idle capacity, missing
  custom/master data, unusual entry patterns, expiring contracts, SLA breaches. Findings become
  Alerts (Helpdesk/Dashboard), each with evidence, confidence, and a suggested action.
- **Workflow authoring**: assistant can draft a workflow (trigger → conditions → actions, stored
  as versioned JSON) which a human reviews and activates; engine executes it with an audit trail,
  idempotent steps, retry, and a dry-run mode.
- **RAG**: Knowledge Vault chunks + embeds into `VECTOR(768)`; retrieval via HNSW + fulltext hybrid
  ranking; answers cite documents with links; no-answer cases say so.

---

## §12. Multi-Lingual, Multi-National, Multi-Currency

- **Locales**: `en` (default), `id`, `ms` in v1; structure supports any locale. UI strings from
  `messages*.properties` (per plugin) with parameter interpolation, plurals, and gender where
  needed; server-side rendering uses the request locale; user profile overrides; `Accept-Language`
  negotiation; admin can upload translations; missing-key fallback to `en` and a build-time audit
  of unused/missing keys.
- **Formatting**: per-locale number/decimal/currency/date/time formats from CLDR data bundled in
  the JDK; currency shown with the *document's* currency, not the viewer's.
- **Multi-country configuration**: per org, enable countries; per country configure: currency,
  default locale/timezone, fiscal calendar (calendar id + start month), tax rules (rates, categories,
  inclusive/exclusive, thresholds, exemptions, reverse charge), e-invoice format (Peppol/UBL,
  local e-Faktur/ZUGFeRD/FatturaPA, custom), statutory document numbering and required fields,
  banking/address format, import/export compliance notes.
- **Tax engine**: rates and categories are data, not code; effective-dated; jurisdiction by site and
  customer; tax-inclusive prices; rounding rules per document (line-level vs total-level,
  half-even/half-up per country); every computed tax line stores the rule snapshot used, so a
  historic document can always be explained.
- **Multi-currency**: transaction currency + functional (org) currency + reporting currency;
  exchange rates from a `RateProvider` SPI (manual, ECB, or plugin-provided); revaluation at period
  close with gain/loss postings; rounding at document and total level.

---

## §13. Security, RBAC, Audit

- Users, roles, groups; role → permission set; **site scope** per user (a user may have different
  roles at different sites) and **country scope**.
- Permissions are capability strings, e.g. `accounting.journal.post`, `inventory.stock.adjust`,
  `order.approve.above:50000`, `admin.plugin.install`. Amount-based approval policies are
  supported by the same permission mechanism.
- Separation of duties: requester ≠ approver for postings above a threshold; configurable.
- Session: server-side in DB (`ub_session`), HttpOnly/Secure/SameSite=Lax cookie, CSRF token on all
  state-changing requests, idle + absolute timeout, "log out all devices", re-authentication for
  sensitive operations.
- API: OAuth2 client-credentials for machine clients; short-lived signed tokens; per-client
  scopes; rate limiting; no token in URLs.
- Audit: append-only `ub_audit_log` (actor, action, entity, before/after JSON diff, IP, UA,
  correlation id, timestamp) + per-entity history views in the UI. Audit rows are never updated
  or deleted by application code.
- Compliance hooks: GDPR-ish data subject export/erasure for personal data in HRM/CRM, retention
  policies per entity type, immutable backup of audit log.

---

## §14. Upgrade with Minimum Disruption

- **SemVer** for core (`4.2.x`) and every plugin; SPI/API version is separate from product version
  and is the compatibility contract.
- **Database**: Flyway; **expand/contract** only:
  1. *Expand* — add new nullable columns/tables/indexes; deploy code that reads both.
  2. *Migrate* — backfill in bounded, resumable batches with progress checkpoints.
  3. *Contract* — switch code to the new column only after all instances run the new version; drop
     the old column **no earlier than 2 releases later**, with a documented checklist.
  - Never destructive DDL in the same release that stops writing the old shape.
  - Migrations run at startup by default (single-instance guard), or as a dedicated job in k8s
    (`job/migrations` with `helm.sh/hook: pre-upgrade`); the app refuses to start on a schema
    version it does not understand, with a clear remediation message.
- **Zero-downtime on Tomcat**: parallel deployment (`manager?path=/ultibiz&version=vNNN`) via
  `context.xml` + `deployOnStartup=false`, session serialization for in-flight sessions, health
  check before cut-over, old context undeploy after drain; document the exact `context.xml`
  snippet and the rollback command.
- **Plugin upgrades**: version-pinned directories, atomic symlink swap, health check, automatic
  rollback to the previous version if the new one fails to start; DB migrations for plugins are
  forward-only and never rolled back automatically.
- **Config & secrets**: externalized (env vars / mounted files / k8s Secrets), with a documented
  precedence order; config changes that require restart are flagged in the admin UI.
- **Upgrades must be documented** in `docs/UPGRADE.md` per release: prerequisites, migration
  steps, breaking changes, rollback plan, verification checklist.

---

## §15. Kubernetes & Operations

- **Artifacts**: multi-stage Dockerfile → `tomcat:11-jre` (or distroless + Tomcat tarball),
  non-root user (uid 10001), read-only root filesystem with writable `tomcat/work`, `temp`, `logs`
  and `${ultibiz.home}` volumes, `HEALTHCHECK`, SBOM + image provenance, pinned base digest.
- **Manifests**: Helm chart `charts/ultibiz` + plain `deploy/k8s/*.yaml`; components:
  - `deployment/web` — Tomcat WAR; `replicas: 2`; `RollingUpdate` `maxSurge 1 / maxUnavailable 0`;
    `preStop` sleep 10 for endpoint drain; `terminationGracePeriodSeconds: 60`.
  - `deployment/worker` — background workers (sync, outbox, insights, imports) with **leader
    election** (DB-backed lease table — no Redis) so only one replica runs cron-like jobs.
  - `job/migrations` — Flyway migrations, `helm.sh/hook: pre-install,pre-upgrade`.
  - `service`, `ingress` (path `/ultibiz`), `networkpolicy`, `poddisruptionbudget`,
    `configmap`, `secret` (or External Secrets), `serviceaccount` (no cluster-admin),
    `hpa` (CPU + custom), `poddisruptionbudget`, `networkpolicy`.
- **Probes**: `/actuator/health/liveness` (no DB dependency), `/actuator/health/readiness`
  (DB reachable + migrations applied + plugins healthy), startup probe with generous budget.
- **Sessions**: DB-backed → no sticky sessions required; scale horizontally.
- **Observability**: Actuator + Micrometer → Prometheus (RED metrics, HikariCP, JVM, Tomcat, jOOQ,
  per-plugin job timers); structured JSON logs to stdout with `correlation_id`; OpenTelemetry
  traces (export to OTLP); Grafana dashboards + alert rules shipped in `deploy/observability/`.
- **Backups**: `tools/backup.sh` (logical dump + binlog-friendly config) and
  `tools/restore.sh`, verified by a restore drill in CI; documented RPO/RTO; retention rotation.
- **Production checklist** in `docs/OPERATIONS.md`: sizing (CPU/RAM per concurrent user), Tomcat
  tuning (`server.xml`, `catalina.properties`, `maxThreads`, `acceptCount`, thread-local cache
  memory), MariaDB tuning (buffer pool, `innodb_flush_log_at_trx_commit`, `max_connections`),
  reverse proxy (TLS, `X-Forwarded-*`, compression, WebSocket/SSE buffering off), and the
  `ULTIBIZ_OPTS` JVM flags used in production.

---

## §16. Repository Layout

```
ultibiz/
├─ pom.xml                      # parent, dependencyManagement, plugin/version policy
├─ docs/
│  ├─ ULTIBIZ_MASTER_PROMPT.md  # this file
│  ├─ ARCHITECTURE.md  ADR/  SECURITY.md  UPGRADE.md  OPERATIONS.md  API.md
├─ ultibiz-spi/                 # stable, versioned extension contracts (no Spring imports)
├─ ultibiz-core/                # org/site, tenant, RBAC, i18n, audit, plugin loader, AI gateway,
│                               # workflow engine, sync engine, numbering, country/tax SPI glue
├─ ultibiz-web/                 # Thymeleaf views, controllers, HTMX fragments, static assets
├─ plugins/
│  ├─ plugin-accounting/  plugin-order/   plugin-manufacturing/ plugin-assets/
│  ├─ plugin-hrm/         plugin-projects/ plugin-crm/         plugin-sales/
│  ├─ plugin-dashboard/   plugin-knowledge-vault/ plugin-helpdesk/ plugin-data-exchange/
├─ tools/                        # vendor-assets.sh, dev-up.sh, backup.sh, restore.sh, seed-demo.sh
├─ deploy/                       # k8s manifests, helm chart, docker-compose, observability
└─ tests/                        # cross-module integration, sync harness, peppol-sim
```

Each plugin mirrors: `src/main/java/<pkg>/{spi,domain,application,infrastructure}`,
`src/main/resources/{db/migration/<plugin>,migrations,static,js,i18n}`,
`src/test/java`, plus `plugin.properties` and its `AutoConfiguration.imports`.

---

## §17. Delivery Plan (phases; each ends green)

| Phase | Scope | Exit gate (all must pass) |
|---|---|---|
| **P0 Foundation** | parent POM, SPI, core skeleton (org/site/user/role/audit), MariaDB schema + Flyway baseline, Tomcat WAR packaging, Thymeleaf shell + Bootstrap vendoring + dark mode, login/roles, health/metrics, Docker Compose + `dev-up.sh`, CI (`mvn -B verify`) | WAR deploys to Tomcat 11, login works, `/actuator/health` green, `mvn -B verify` green, RSS ≤ 350 MB |
| **P1 Master data & multi-site** | product/party/warehouse/rack/dock/line model, site-scoped repositories + scoping tests, numbering engine, country + currency + tax SPI skeleton, i18n pipeline (en/id), CSV import/export | Scoping tests prove no cross-site leakage; numbering sequence correct under concurrency |
| **P2 Accounting** | CoA, journals, postings, periods/close, AR, AP, tax engine + 2 country packs (e.g. ID + generic EU), multi-currency + revaluation, bank, audit export | Double-entry invariant holds under property tests; posting reversal path works; tax pack fixtures pass |
| **P3 Sales & Order Management** | price lists, quotations, sales orders, approvals, allocation/shipments, purchase orders, receipts, invoices hand-off | Order→shipment→invoice→ledger trace is consistent; approval thresholds enforced |
| **P4 Manufacturing & Assets** | BOM/routing, work orders, production lines, material issue/return, WIP/costing, basic MRP, lots/serial, assets register + depreciation + maintenance | BOM explosion correct; WO→goods receipt→inventory→GL reconciles; depreciation schedule matches fixtures |
| **P5 Data Exchange** | BDM, inbox/outbox, REST/JSON adapter, Peppol UBL adapter + simulator + validation (XSD/Schematron/Saxon), EDI X12/EDIFACT, mapping console, partner registry | Round-trip golden files; duplicate delivery never double-posts; invalid invoice rejected with fatal-rule reason |
| **P6 Offline & Sync** | service worker, IndexedDB outbox, push/pull endpoints, conflict policy engine, Conflict Center, sync engine + tests | Two-client convergence test passes; offline draft round-trips; financial posting blocked offline |
| **P7 AI Assistant** | AI gateway + providers, side drawer + command palette, `AiToolProvider` per module, approvals for writes, insight engine, RAG in Knowledge Vault, prompt/usage audit | Assistant answers with citations; write actions require confirmation and are audited; provider outage degrades gracefully |
| **P8 HRM, Projects, CRM, Dashboard, Knowledge Vault, Helpdesk** | §8 modules 5,6,7,9,10,11 | Each module ships with its own acceptance checklist + role matrix |
| **P9 Hardening & Release** | k8s manifests + Helm, observability dashboards, backup/restore drill, security review (OWASP), load test (e.g. 200 concurrent users), perf budget, upgrade drill on a copy of prod data, docs | `docs/OPERATIONS.md` + `docs/UPGRADE.md` complete; restore drill verified; upgrade with zero downtime demonstrated; load test meets targets |

P9 targets (state them in the report, measure them): p95 page render < 400 ms (list of 50 rows),
posting a journal < 150 ms, sync of 10 000 changes < 60 s, startup to ready < 45 s,
200 concurrent users with ≤ 350 MB RSS on a 4 vCPU / 8 GB node.

---

## §18. Working Method (how you must behave)

1. **Read first**: `docs/ULTIBIZ_MASTER_PROMPT.md`, the module's `README`, and existing code
   conventions before writing. Match the existing style; don't introduce a new pattern unilaterally.
2. **Task granularity**: work in slices that each end compiling and tested. Never leave the tree
   broken at the end of a turn.
3. **Plan before writing**: for any task touching > 3 files or adding a schema change, write a short
   plan (bullets) at the top of your reply, then execute it.
4. **Verify**: run `mvn -B verify` (or the narrowest meaningful gate) and paste the relevant output.
   No "should work" claims.
5. **Migrations**: one Flyway script per logical change; reversible where possible, forward-only
   where not; note in the PR description how it respects expand/contract.
6. **Tests**: unit tests for every business rule; Testcontainers (MariaDB) for repository/IT
   tests where the SQL matters; a deterministic seed fixture for demos and e2e.
7. **Docs as you go**: update the module README and `docs/` when behaviour changes; a stale doc is
   a bug.
8. **Escalation, don't degrade**: if a dependency, version, or approach is blocked, report the
   blocker and enumerate **at least 3** concrete alternatives with trade-offs — never silently pick
   a worse option.
9. **Honest status**: distinguish `done` / `partial` / `not started`; list known limitations and
   follow-up items. Never present scaffolding as a finished feature.
10. **Report format** (every final message):
    ```
    2026-11-25 12:29 WIB
    Changed:  path/to/File.java (+120/-30), path/to/Other.sql
    Verified: mvn -B verify → BUILD SUCCESS (412 tests, 0 failures) | curl -s localhost:8080/ultibiz/actuator/health → {"status":"UP"}
    Open:     <remaining items, or "none">
    ```
11. **Ask only when it changes the data model, money handling, security posture, or scope.** For
    everything else choose the conventional option, document it in the ADR, and continue.

---

## §19. Definition of Done (any feature)

- [ ] Compiles; `mvn -B verify` green; new code has tests (business rules unit-tested).
- [ ] DDL only via Flyway; migration is expand/contract-safe; rollback documented.
- [ ] Permissions enforced server-side; no UI-only checks; audit record written.
- [ ] i18n keys for every new string (en + id); no hard-coded text.
- [ ] Works in dark mode; responsive at 360 px and 1440 px; keyboard accessible.
- [ ] Offline behavior defined (works / blocked-with-message / queued) and communicated in the UI.
- [ ] API documented (OpenAPI); module README updated.
- [ ] No new runtime dependency without a written justification in the ADR.
- [ ] Tests pass in CI; memory footprint impact measured.

---

## §20. Open Decisions (resolve in P0, document as ADRs)

1. jOOQ code-generation vs. hand-written SQL fragments for core repositories (prefer jOOQ
   codegen against a dev MariaDB schema; keep a checked-in DDL snapshot for offline builds).
2. Session storage: DB (default) vs. pluggable (keep DB; Redis optional later).
3. AI provider default (local llama.cpp/OpenAI-compatible vs. cloud) and default embedding model
   (must be 768-dim to match `VECTOR(768)`).
4. Peppol: external AP vs. embedded AP (start with external AP + simulator; embedded AP is a
   separate product decision).
5. Embedding storage for non-Knowledge-Vault entities (v1: Knowledge Vault only).

---

*UltiBiz Master Prompt v1.0 — 2026-11-25. Amend via PR to `docs/ULTIBIZ_MASTER_PROMPT.md`;
every agent must read the current file before acting.*
