# ADR-002 — Master-data scoping semantics and the numbering engine

- **Status:** accepted
- **Date:** 2026-11-25
- **Phase:** P1

## Context

Multi-site ERP: one organization, many sites (factories, distribution centres).
Every site has warehouses with racks, bins and docks. Master data (products,
parties) is either owned by one site or shared by the whole organization.

Document numbers (SO-, PO-, INV-, GRN-, MO-) must be unique, contiguous and
statutory per country.

## Decision

### 1. Shared vs site-owned master data

Master tables carry `organization_id` + `site_id`, where **NULL `site_id` means
"shared by every site of the organization"**. A generated column `site_key`
(`IFNULL(site_id, 0)`) backs the unique key, because MySQL/MariaDB treats NULL
as distinct in unique indexes and would otherwise allow duplicate codes.

Scoping is applied in the repository layer, never in the UI:

| Scope | Fragment |
|---|---|
| organization-wide role | `organization_id = ?` |
| site-restricted role | `organization_id = ? AND (site_id IS NULL OR site_id IN (?, …))` |
| deny-all (no site assigned) | `1 = 0` |

`SiteScopedSql.masterDataClause` implements the master-data variant;
`SiteScopedSql.whereClause` stays strict (`site_id IN (…)`) for transactional
data where every row belongs to exactly one site.

**Why:** a shared product (e.g. a bought-in bearing) must be usable from every
site without duplicating it, while a site-specific product (e.g. a packaging
label for one plant) must never leak into another plant's stock.

### 2. Numbering engine

The counter is advanced inside a single transaction:

1. `SELECT … FOR UPDATE` on the sequence row — a **locking read**, and the
   *first* statement of the transaction.
2. compute the period key (`NONE` / `MONTHLY` / fiscal `YEARLY`); a changed
   period key restarts the counter at `start_at`.
3. `UPDATE … SET counter = ?`.

The transaction runs at `READ COMMITTED`.

**Why these two details:**
- Under MariaDB's default `REPEATABLE READ`, a plain `SELECT` first creates a
  snapshot; a later locking read of a row another transaction already changed
  fails with error 1020 *"Record has changed since last read"*. Making the
  locking read the first statement avoids the conflict, and `READ COMMITTED`
  makes the locking read see the latest committed row.
- The implementation uses **Spring JDBC** (`JdbcTemplate`), not jOOQ, for the
  numbering path: inside a Spring transaction every statement of the thread
  runs on the same physical connection, so the row lock is really held until
  commit. With jOOQ's default transaction provider the inner statements could
  run on different connections, and the lock was released immediately — the
  integration test with 200 parallel callers produced duplicate numbers.
- The row lock serialises concurrent callers, so numbers are contiguous (no
  gaps) and unique. No `LAST_INSERT_ID()` trick, no table-level lock.

**Rejected alternatives**
- `UPDATE … SET counter = counter + 1` + `SELECT counter`: two round trips, the
  value read may already belong to the next caller.
- `LAST_INSERT_ID(counter + 1)`: connection-scoped trick, breaks with pooled
  connections and needs the period logic in a second statement anyway.
- Optimistic retry loop: more moving parts, and the gap between two attempts
  is exactly what document numbering must not have.

### 3. Site-scoped sequences win

A sequence may be organization-wide (`site_id IS NULL`) or site-scoped. The
lookup prefers the site-scoped row, so a factory can run its own numbering
without affecting the other sites of the same organization.

### 4. Tax rules are effective-dated and snapshotted

`tax_rule` rows carry `effective_from` / `effective_to`; the lookup picks the
highest-priority rule valid on the document date. Every computed tax line
stores the rule it used (`tax_rule_snapshot`), so a later rate change never
rewrites a document that was already issued.

## Consequences

- Master data reads need `SiteScopedSql.masterDataClause`, not the strict
  variant — a mismatch would hide shared products from site users.
- The numbering path is database-bound: throughput is one row-lock at a time
  per sequence. That is acceptable for document numbers (tens per minute);
  high-volume keys (stock movements) should use a different mechanism in P3.
- Integration tests are mandatory for anything that touches scoping or
  numbering; they run against a real MariaDB (`-Dultibiz.it.jdbcUrl=…`).
