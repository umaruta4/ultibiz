# UltiBiz

Open-source, modular ERP for multi-site manufacturing and distribution.
Java 21 · Spring Boot 4.2.0 (WAR on Apache Tomcat 11) · MariaDB 11.8+ ·
Thymeleaf 3.1 + HTMX 2 + Bootstrap 5.3 (vendored — no CDN at runtime, no
Node/npm/bundler). Runs at root context (`/`).

## Status

| Phase | Scope | State |
|---|---|---|
| **P0** | Foundation: Maven multi-module, schema baseline, security/login, DB-backed sessions, web shell, dark mode, audit, health probes, Flyway, deploy tooling | **done** |
| **P1** | Master data + multi-site (product, party, warehouse/rack/bin/dock, production line, work center), numbering engine, country/tax SPI, CSV import/export, master-data screens | **done** |
| **P2** | Accounting: chart of accounts, fiscal periods, double-entry posting engine, reversals, tax packs (ID PPN + EU VAT), multi-currency, trial balance, accounting screens | **done** |
| **P2b** | AR/AP: invoices, bills, receipts, payments, allocations, ageing, three-way match, bank statement import + reconciliation | **done** |
| **P3** | Sales & purchase orders, goods receipts, stock (moving average), BOM + work orders, order/inventory/manufacturing screens | **done** (core) |
| P4 | Asset management + HRM | planned |
| P5 | Projects + CRM + sales pipeline | planned |
| P6 | Dashboard + Knowledge Vault (RAG) + Helpdesk | planned |
| P7 | Data exchange: EDI, Peppol UBL, REST/JSON | planned |
| P8 | Offline sync, admin console, upgrades | planned |
| P9 | AI assistant, Kubernetes hardening, performance | planned |

## What P2b delivers

- **Customer invoices**: issued from a shipped sales order; they post a real
  journal (debit receivables, credit revenue, credit tax payable) and the
  invoice keeps the journal reference.
- **Supplier bills**: issued from a received purchase order; the journal books
  the inventory/expense against the payable.
- **Receipts and payments**: money in and money out, each with its own journal;
  the amount is allocated to a chosen invoice/bill first and to the oldest open
  ones afterwards. The document status follows: `OPEN` → `PARTIAL` → `PAID`.
- **Ageing**: receivables and payables with days overdue and the total
  outstanding.
- **Three-way match**: bill against purchase order and goods receipt, with a
  configurable tolerance (percent and absolute amount, per organization).
- **Bank and cash**: bank/cash accounts linked to a ledger account, statement
  import from CSV and a reconciliation that matches statement lines against the
  receipts and payments by amount and date.
- **Screens**: `/accounting/ar`, `/accounting/ar/aging`, `/accounting/ap`,
  `/accounting/ap/aging`, `/accounting/ap/{id}/match`, `/accounting/bank`.

Details: schema migration `V202611250005__p2b_ar_ap.sql`.

## What P9 delivers (hardening & release)

- **Rate limit** on the public endpoints (the customer portal, the inbound webhook): per IP and path, `429` with `Retry-After`.
- **Backup + restore drill** as scripts, not a promise: `tools/backup.sh` writes a consistent dump (`--single-transaction`) plus the report files, `tools/restore-drill.sh` restores it into a scratch database and compares the row counts table by table — the drill is green on the production instance.
- **Release gate** `tools/release-check.sh` (the i18n audit, the build with the tests, the migration check).
- **Documents**: `docs/RUNBOOK.md` (the services, the deploy, the rollback, the backup, the schedulers, the secrets, the troubleshooting table), `docs/SECURITY.md` (what protects the application and where the limits are), `docs/RELEASE-NOTES-P8.md`.

## What P8 delivers (all 6 plugins: HRM, Projects, CRM, Dashboard, Knowledge Vault, Helpdesk)

**Projects.** Delivery work with real money behind it:

- **Projects** with customer, manager, budget, currency and a billing rate per hour; statuses ACTIVE / ON_HOLD / CLOSED.
- **WBS**: a tree per project with budgeted hours.
- **Milestones**: billing events on the PENDING → REACHED → INVOICED flow. Invoicing a reached milestone **posts a real journal** (Dr Accounts Receivable / Cr Sales Revenue) through the regular posting engine — the milestone carries the journal id, and the books reflect the revenue immediately.
- **Resource assignments** with a capacity rule: an employee is never allocated more than the weekly hours of their contract across overlapping assignments (the conflict is refused with the exact numbers).
- **Cost & revenue tracking**: the approved HRM timesheets of the project code become the labour cost at the contractual hourly rate (base / (weekly hours × 4.348125)); billable hours at the billing rate plus invoiced milestones become the revenue; the report shows hours, cost, revenue, profitability and budget usage per project and per employee.
- One end-to-end IT: timesheet approved in HRM → project cost → milestone invoiced → journal posted → profitability.

**Helpdesk.** The service desk, from the customer to the SLA:

- **Queues with SLA tiers**: every queue carries its own response and resolution window (support: 8 h / 72 h, critical: 1 h / 8 h) and its escalation window; the deadlines are computed by the **database clock**, so the application server may run in another time zone.
- **Intake** from the portal, e-mail, phone or chat; the priority is read from the words of the requester ("server down" → URGENT).
- **Triage, assignment and the status flow**: NEW → TRIAGED → ASSIGNED → IN_PROGRESS → WAITING → RESOLVED → CLOSED (a resolved ticket can be reopened); the flow is enforced.
- **Replies** in two flavours: public (the customer sees it, it stamps the first response) and internal notes (the team only).
- **Escalation**: a manual escalation with a reason, and an SLA monitor that escalates the overdue tickets (named lock, so two replicas never escalate twice).
- **Knowledge suggestions**: the vault documents that match the subject and the description of the request are attached to the ticket.
- **Customer portal**: a read-only page per ticket — the token in the link is the secret, no account needed (`/portal/{token}`); the customer sees the public thread and can reply.
- One end-to-end IT: intake (SLA of the queue) → urgent detection → triage → assignment → public reply → resolve/close → portal link → knowledge suggestions → SLA monitor.

**Knowledge Vault.** The company memory, with the rules of a real document store:

- **Documents with versions**: every revision is kept (`kv_version`), the current version is the live one; a new version **re-indexes the RAG corpus** so the assistant cites the newest text.
- **Folders** (a tree with no cycle, max 5 levels) and **tags**.
- **Search**: MariaDB FULLTEXT over the versions (boolean mode, prefix terms) — an archived document leaves the search.
- **Legal hold**: a document on hold is never touched by the retention run.
- **Retention policies**: per folder or organisation, ARCHIVE or DELETE after N months; a scheduled/one-off run reports what it archived, deleted and kept.
- One end-to-end IT: document → corpus chunking → new version → re-index → fulltext search → legal hold → retention run → archived document leaves the search.

**Dashboard.** The cockpit, role-aware:

- **KPI tiles** from the real ERP data — revenue this month, overdue receivables, cash on hand, open sales/purchase orders, stock value, headcount, leave requests waiting, CRM pipeline, project budget. Every tile carries the permission codes that make it visible, so a role sees its own cockpit; every tile has a **drill-through** link into the module it measures.
- **Thresholds & alerts**: a metric above (or below) a configured value raises an alert with its severity (INFO / WARNING / CRITICAL), sorted by severity.
- **Saved views**: a named filter set per module, personal or shared with the organisation.
- **Scheduled reports**: a subscription per user (daily / weekly / monthly — the weekly run always lands on Monday), the report is written to disk as CSV or a print-ready HTML page (the browser can save it as PDF) and offered for download; when `ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK` is set the report is also posted to that webhook.
- **Insight board** on the same page: it consumes the `AiInsightProvider` rules (negative stock, below safety stock, overdue AR) with evidence, confidence and a suggested action.
- A scheduler writes the due reports under a MariaDB named lock, so two replicas never write the same report twice.

**CRM.** The commercial side, from a stranger to a signed order:

- **Leads** with company, contact, source (manual / web / referral / campaign), territory and campaign; a lead qualifies only with a way to reach it, and converts only from QUALIFIED.
- **Conversion** creates the customer in the master data (`CUST-<lead code>`) and opens an opportunity at the first pipeline stage — no duplicate customer data.
- **Pipeline** (NEW → QUALIFIED → PROPOSAL → NEGOTIATION → WON / LOST) with a probability per stage: the pipeline never moves backwards, a lost deal needs a reason, a won deal needs an amount. The board shows the weighted value (amount × probability).
- **Quote hand-off to Sales**: an open opportunity with an amount becomes a real **draft sales order** with one line at the opportunity value — Sales confirms, delivers and invoices it from there; the opportunity carries the order id and refuses a second hand-off.
- **Activities** (call / meeting / e-mail / task) per lead, opportunity or contact, with due dates and completion; **campaigns** and **territories** for the campaigns and the market split.
- One end-to-end IT: lead → qualify → convert (party + opportunity) → pipeline rules → hand-off (real sales order) → won → activities.

**HRM.** The people side of the company, end to end:

**Helpdesk.** The service desk, from the customer to the SLA:

- **Queues with SLA tiers**: every queue carries its own response and resolution window (support: 8 h / 72 h, critical: 1 h / 8 h) and its escalation window; the deadlines are computed by the **database clock**, so the application server may run in another time zone.
- **Intake** from the portal, e-mail, phone or chat; the priority is read from the words of the requester ("server down" → URGENT).
- **Triage, assignment and the status flow**: NEW → TRIAGED → ASSIGNED → IN_PROGRESS → WAITING → RESOLVED → CLOSED (a resolved ticket can be reopened); the flow is enforced.
- **Replies** in two flavours: public (the customer sees it, it stamps the first response) and internal notes (the team only).
- **Escalation**: a manual escalation with a reason, and an SLA monitor that escalates the overdue tickets (named lock, so two replicas never escalate twice).
- **Knowledge suggestions**: the vault documents that match the subject and the description of the request are attached to the ticket.
- **Customer portal**: a read-only page per ticket — the token in the link is the secret, no account needed (`/portal/{token}`); the customer sees the public thread and can reply.
- One end-to-end IT: intake (SLA of the queue) → urgent detection → triage → assignment → public reply → resolve/close → portal link → knowledge suggestions → SLA monitor.

**Knowledge Vault.** The company memory, with the rules of a real document store:

- **Documents with versions**: every revision is kept (`kv_version`), the current version is the live one; a new version **re-indexes the RAG corpus** so the assistant cites the newest text.
- **Folders** (a tree with no cycle, max 5 levels) and **tags**.
- **Search**: MariaDB FULLTEXT over the versions (boolean mode, prefix terms) — an archived document leaves the search.
- **Legal hold**: a document on hold is never touched by the retention run.
- **Retention policies**: per folder or organisation, ARCHIVE or DELETE after N months; a scheduled/one-off run reports what it archived, deleted and kept.
- One end-to-end IT: document → corpus chunking → new version → re-index → fulltext search → legal hold → retention run → archived document leaves the search.

**Dashboard.** The cockpit, role-aware:

- **KPI tiles** from the real ERP data — revenue this month, overdue receivables, cash on hand, open sales/purchase orders, stock value, headcount, leave requests waiting, CRM pipeline, project budget. Every tile carries the permission codes that make it visible, so a role sees its own cockpit; every tile has a **drill-through** link into the module it measures.
- **Thresholds & alerts**: a metric above (or below) a configured value raises an alert with its severity (INFO / WARNING / CRITICAL), sorted by severity.
- **Saved views**: a named filter set per module, personal or shared with the organisation.
- **Scheduled reports**: a subscription per user (daily / weekly / monthly — the weekly run always lands on Monday), the report is written to disk as CSV or a print-ready HTML page (the browser can save it as PDF) and offered for download; when `ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK` is set the report is also posted to that webhook.
- **Insight board** on the same page: it consumes the `AiInsightProvider` rules (negative stock, below safety stock, overdue AR) with evidence, confidence and a suggested action.
- A scheduler writes the due reports under a MariaDB named lock, so two replicas never write the same report twice.

**CRM.** The commercial side, from a stranger to a signed order:

- **Leads** with company, contact, source (manual / web / referral / campaign), territory and campaign; a lead qualifies only with a way to reach it, and converts only from QUALIFIED.
- **Conversion** creates the customer in the master data (`CUST-<lead code>`) and opens an opportunity at the first pipeline stage — no duplicate customer data.
- **Pipeline** (NEW → QUALIFIED → PROPOSAL → NEGOTIATION → WON / LOST) with a probability per stage: the pipeline never moves backwards, a lost deal needs a reason, a won deal needs an amount. The board shows the weighted value (amount × probability).
- **Quote hand-off to Sales**: an open opportunity with an amount becomes a real **draft sales order** with one line at the opportunity value — Sales confirms, delivers and invoices it from there; the opportunity carries the order id and refuses a second hand-off.
- **Activities** (call / meeting / e-mail / task) per lead, opportunity or contact, with due dates and completion; **campaigns** and **territories** for the campaigns and the market split.
- One end-to-end IT: lead → qualify → convert (party + opportunity) → pipeline rules → hand-off (real sales order) → won → activities.

**HRM.** The people side of the company, end to end:

- **Employee master** with org structure (positions with parents, departments, cost centres) — the personal fields (e-mail, phone, national id, birth date) are explicitly in the GDPR scope.
- **Contracts**: permanent / fixed-term / intern / freelance with salary, currency and weekly hours.
- **Leave**: working-day computation (weekends skipped), overlap detection against open requests, and a per-type annual balance enforced on submit; approve/reject with audit.
- **Timesheets**: ≤ 24 h per entry, never in the future, unique per (employee, day, project) — approved hours feed the project costs.
- **Expense claims**: the claim total is always the sum of its lines; submit → approve/reject → (paid in accounting).
- **H&S incident register** (injury / near-miss / equipment / environment with severity, OPEN → INVESTIGATING → CLOSED) — feeds the helpdesk.
- **Payroll run & export**: one run per period (YYYY-MM), net = base + allowances − deductions, cost-centre allocation by approved hours, CSV export; an exported run is closed forever.
- **GDPR hooks**: personal-data export (JSON) and erasure that anonymises the personal fields while the financial rows (contracts, payroll, expenses) stay untouched — the books never change; every erasure is logged.
- Permission matrix, 11 business-rule unit tests, one end-to-end IT (hire → leave → timesheet → expense → payroll export → GDPR), i18n EN+ID, dark-mode UI.

## What P7 delivers

**AI assistant.** The assistant lives in a drawer on every screen (header button or Ctrl/⌘K), answers questions about the data with citations, and can prepare write actions — a human always confirms them.

- **Provider gateway with SPI**: OpenAI-compatible servers (OpenAI, llama.cpp, vLLM, Ollama, any new-api style gateway), Anthropic and a local Echo provider, behind one `AiGateway` with timeouts, retries and a circuit breaker. When the provider is down the assistant degrades gracefully; the ERP never does.
- **Per-organisation configuration** (`/ai/usage`): provider, base URL, model, temperature, timeouts and an *allow external providers* switch that is **off by default** — data stays on the chosen endpoint. API keys are stored AES-encrypted (`ULTIBIZ_AI_ENCRYPTION_KEY`); without the key no key can be saved.
- **PII redaction** before anything leaves the box (country-aware rules: e-mail, phone numbers, Indonesian NPWP).
- **Tools**: read tools (`find_invoices`, `find_bills`) run inline and are logged; the write tool (`draft_journal`) returns a **proposal with a diff** (`/ai/actions/propose`) that only posts through the regular `JournalService` after a human confirms it (`/ai/actions/{id}/confirm`). Every prompt, answer and tool call is stored with model, token usage and latency, and the audit trail carries the AI conversation id.
- **Insight engine**: a scheduler (DB-lock guarded, so one instance at a time) runs rule providers — negative stock, below-safety-stock, overdue AR — and files alerts with evidence, confidence and a suggested action (`/ai/insights`, manual run via POST `/ai/insights/run`).
- **RAG knowledge base**: a knowledge vault (`knowledge_document` / `knowledge_chunk`) is chunked and embedded (768-dim hashing encoder with character trigrams); retrieval is hybrid — MariaDB `FULLTEXT` plus cosine distance — and answers cite the documents. A question without a match is answered honestly ("not found in the knowledge base").
- **Note on MariaDB 11.8**: the build on Ubuntu 24.04 ships the `VECTOR` type but not the vector functions, so the native HNSW index is deferred (documented in the migration); the retrieval runs on the text copy of the embedding and the fulltext index, which keeps the whole feature portable.

## What P6 delivers

- **Offline-first client** (`static/js/sync.js`): a dependency-free IndexedDB
  wrapper with three stores (outbox, snapshots, meta), the offline banner, the
  status bar (pending / failed with the operator-readable reason) and the
  policy that refuses the online-only forms while offline.
- **Service worker and PWA**: `static/sw.js` precaches the app shell and the
  vendored assets in a versioned cache (stale while revalidate, the cached
  shell as the navigation fallback), plus `manifest.webmanifest` and the
  installable icons.
- **Server side**: `ub_change_log` (a monotonic sequence per organisation),
  `ub_idempotency` (every pushed operation is applied exactly once),
  `ub_sync_entity` (the documents that may be created offline), `ub_sync_queue`
  (the retry queue) and `ub_sync_conflict` (the conflicts).
- **Sync API**: `POST /api/sync/push`, `GET /api/sync/changes?since=`,
  `GET /api/sync/pull?cursor=` and `GET /api/sync/status`; every pushed
  operation gets its own answer — APPLIED, CONFLICT, REJECTED or DUPLICATE.
- **Conflict policy**: last writer wins for the low-risk fields, a field-level
  merge for disjoint edits, and the server wins plus an operator decision for
  the numeric, financial and status fields — a numeric field is never silently
  overwritten, so an update can not be lost.
- **Conflict centre** (`/sync/conflicts`): the field diff with keep mine,
  keep theirs and merge.
- **Offline policy**: accounting postings, stock transfers, invoices, bills and
  receipts are online-only and are refused with a clear reason; drafts,
  notes, timesheets, attendance, stock counts and tickets work offline.

## What P5 delivers

- **Canonical document model (BDM)** — one Java model (`CanonicalDocument`)
  that every adapter maps into and out of: document types (invoice, credit
  note, order, despatch, purchase invoice, statement), parties with
  electronic addresses, lines, tax breakdown, totals, payment terms and
  references. Totals are derived from the lines, so every protocol renders the
  same numbers.
- **Inbox/outbox engine** — `RECEIVED → PARSED → VALIDATED → MAPPED → APPLIED`
  for inbound, `QUEUED → SENT` for outbound, with an **idempotency key** per
  (organisation, partner, direction): a re-delivery returns the existing
  message and never creates a second document. Failures retry with exponential
  backoff, land in the **dead-letter queue** after the maximum attempts and can
  be replayed by an operator.
- **Adapters**
  - *REST/JSON* — the payload is the canonical JSON; the inbound webhook is
    `POST /api/exchange/inbound/{partnerCode}` with `X-Idempotency-Key` and a
    shared secret (`X-Exchange-Secret`); the loopback simulators live under
    `/api/exchange/sim/**`.
  - *Peppol UBL* — UBL 2.1 Invoice/Credit Note per Peppol BIS Billing 3.0
    (EN 16931) with `cbc:EndpointID` + `schemeID`, VAT breakdown and
    `CustomizationID`/`ProfileID`; the validation reports the **exact EN 16931
    rule id** (BR-02 … BR-27, BR-CO-25, PEPPOL-EN16931-R010/R020) and only
    fatal rules reject.
  - *EDI* — a hand-written X12 parser (ISA/GS/GE/IEA envelope, 850/810) with
    997 functional acknowledgements and a hand-written EDIFACT parser
    (UNB/UNH/UNT/UNZ, ORDERS/INVOIC) with CONTRL acknowledgements.
  - *CSV* — flat files with one row per line and the header fields repeated.
- **Console** — partner registry, document browser (raw payload, canonical
  mapping, validation report), the mapping test-run console and the monitoring
  dashboard (throughput per status, dead-letter count).
- **Scheduler** — the retry batch runs under a MariaDB named lock
  (`GET_LOCK`), so two replicas never deliver the same message twice.

## What P4 delivers

- **Asset register** (`/assets`): assets in categories with the depreciation
  rule of the category — straight line, declining balance or units of
  production. Registration, transfer between sites, disposal with the
  proceeds-versus-book-value gain/loss posted to the ledger.
- **Monthly depreciation run** (`/assets/depreciation`): one journal per
  period for the whole register, idempotent (a period cannot be depreciated
  twice); the book value never drops below the salvage value.
- **Maintenance** (`/assets/maintenance`): recurring plans per category,
  scheduled jobs with due dates and completion cost, meter readings that drive
  the units-of-production depreciation.
- **MRP suggestions** (`/planning/mrp`): net requirement per product = open
  sales demand + safety stock − on hand − on order, rounded up to the minimum
  order quantity, suggested date = today + lead time.
- Schema for the next step is already in place: multi-level BOM lines
  (phantoms), engineering change notices, shift calendar, lot/serial
  traceability on stock movements and receipts.
- The sidebar groups its submenus behind collapsible sections with a chevron;
  the group of the current page opens automatically.

## What P3 delivers

- **Sales orders**: line totals with discounts, tax per the country pack, and a
  **credit limit check** that refuses to store an over-limit order.
- **Purchase orders**: receipts book the goods into stock and update the
  received quantities; partial receipts keep the order `PARTIALLY_RECEIVED`.
- **Stock**: `stock_balance` with a **moving average cost** and an immutable
  `stock_movement` ledger; an issue that would drive a balance negative is
  refused. The balance row is locked before it changes.
- **Goods receipts**: one document per physical movement (purchase, delivery,
  production), linked to its source order.
- **Manufacturing**: a work order explodes the bill of material into material
  requirements (with scrap), the release verifies that the materials are
  really in stock, the issue takes them out, and the completion books the
  finished goods in at the average material cost.
- **Screens**: sales/purchase orders (list, form, detail with confirm /
  deliver / receive), stock overview, work orders (list, form, detail with
  release / issue / complete).

Details: schema migration `V202611250004__p3_orders_stock.sql`.

### Known P3 gaps

- Deliveries and receipts act on the whole outstanding quantity per line, not
  on a chosen quantity per line.
- There is no stock transfer between warehouses and no stock-taking (cycle
  count) yet.
- Routing / capacity planning and back-flushing are not implemented.
- The AR / AP side of an order (invoice, allocation, ageing, three-way match)
  is the next accounting slice.

## What P2 delivers

- **Chart of accounts**: hierarchical, organization-scoped, optional country,
  postable flag; seeded with a standard Indonesian chart of accounts.
- **Fiscal periods**: per country and month, `OPEN` / `CLOSED` / `REOPENED`;
  closing is audited, reopening needs the permission
  `accounting.period.reopen` and is audited too.
- **Posting engine**: a journal is posted only when it balances, every line
  carries exactly one side, the account is postable and the period is open.
  Header, lines, document number and audit entry are written in **one**
  transaction with the period row locked (`SELECT … FOR UPDATE`).
- **Immutability**: posted journals are never edited; a correction is a
  reversal that mirrors the lines and marks the original `REVERSED`.
- **Tax packs** behind the `TaxPack` SPI: Indonesia PPN (11 % / 2 % / exempt)
  and an EU VAT pack (20 %, document-level rounding), plus a default pack that
  reads the effective-dated rules from the database.
- **Multi-currency**: `FxService` + `FxRateProvider` SPI; the ledger keeps the
  transaction amounts *and* the functional-currency amounts.
- **Screens**: chart of accounts, journal list, journal editor with a live
  balance check, journal detail with reversal, fiscal period calendar and the
  trial balance.

Details: [`docs/P2-ACCOUNTING.md`](docs/P2-ACCOUNTING.md) ·
[`docs/adr/ADR-003-posting-engine.md`](docs/adr/ADR-003-posting-engine.md)

### Known P2 gaps

- AR / AP (invoices, bills, allocations, ageing, three-way match), bank
  statements and reconciliation are not built yet — they are the next slice.
- Year-end closing and carry-forward are not automated; a period can be closed
  and reopened by hand.
- No approval thresholds: posting rights come from the role, not from an
  amount limit.

## What P1 delivers

- **Master data**: products (UOM, barcode, batch/serial flags, standard cost),
  parties (tax id, credit limit, payment terms), warehouses with racks, bins
  and docks, production lines, work centers, work calendar.
- **Multi-site**: every master row carries `organization_id` + `site_id`;
  `site_id IS NULL` = shared by all sites of the organization. Scoping is
  enforced in the repository layer (`SiteScopedSql`), never in the UI.
- **Numbering engine**: per document type + scope, prefix, padding, monthly /
  fiscal-year restart, concurrency-safe (row lock, single transaction).
  200 parallel callers produce contiguous, duplicate-free numbers.
- **Tax SPI**: `TaxRuleProvider` with effective-dated rules; every computed
  line snapshots the rule it used.
- **CSV import/export** for products and parties with a row-level validation
  report, downloadable error CSV and dry-run mode.
- **Screens**: `/master/products` (list, search, filter, paging, export, import,
  create/edit form), `/master/parties`, `/master/warehouses` (with racks, bins
  and docks), `/master/production-lines` (with work centers).

Details: [`docs/P1-MASTER-DATA.md`](docs/P1-MASTER-DATA.md) ·
[`docs/adr/ADR-002-master-data-scoping-and-numbering.md`](docs/adr/ADR-002-master-data-scoping-and-numbering.md)

### Known P1 gaps

- Detail/edit forms exist for products only; parties, warehouses and production
  lines are read-only in the UI (data comes from the migration or CSV import).
- Column chooser, saved filters per user and print view are not implemented.
- Per-module message bundles (`messages-masterdata.properties`) are not split
  out yet — all keys live in the two main bundles.

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
database, so they never run by accident in CI). The database is prepared by
Flyway itself (`clean` + `migrate`), so the schema under test is always the
migration scripts:

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

## Auditing

```bash
tools/i18n-audit.sh    # every key present in en + id, every #{key} resolvable
```

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
