# The release notes — P8

## The six plugins

| Plugin | What it adds |
| --- | --- |
| HRM | employees, contracts, leave, timesheets, expenses, H&S incidents, payroll with a CSV export, GDPR export and erasure |
| Projects | projects with a WBS, budgets, billing milestones that post a real journal, resource capacity, cost and profitability from the approved timesheets |
| CRM | leads, pipeline with a weighted value, conversion into a customer, the quote hand-off to Sales as a draft order, activities, campaigns, territories |
| Dashboard | role-based KPI tiles with drill-through, threshold alerts, saved views, scheduled reports with a real file delivery, the insight board |
| Knowledge Vault | versioned documents, folders, tags, FULLTEXT search, legal hold, retention policies, the RAG corpus re-indexed on every version |
| Helpdesk | queues with SLA tiers, intake, triage, assignment, public and internal replies, escalation, knowledge suggestions, the read-only customer portal |

## The migrations

`V202611250010` … `V202611250015` — they only add tables and columns, no data
is moved or deleted; every new table is empty on an existing installation
except the seeded catalogue (queues, pipeline stages, KPI tiles, thresholds,
retention policies, the demo project and the demo ticket).

## The breaking changes

None in the API of the existing modules. The new pages are new routes:
`/hr`, `/projects`, `/crm`, `/dashboard`, `/knowledge`, `/helpdesk` and the
public `/portal/{token}`.

## The upgrade notes

- The instance needs the `ULTIBIZ_REPORT_DIR` directory (the default is
  `/home/sufehmi/ultibiz-reports`); the service creates it when it may.
- The rate limit protects the public endpoints; raise `ULTIBIZ_RATE_LIMIT_REQUESTS`
  when a partner sends more.
- The report delivery is inactive until `ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK` is set.

## The test gate

`tools/release-check.sh` — the i18n audit, the build with the tests (the
integration tests run against a real MariaDB) and the migration check.
