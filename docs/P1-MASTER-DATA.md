# P1 — Master data, multi-site, numbering, tax SPI

## Schema (migration `V202611250002__p1_master_data.sql`)

| Table | Purpose | Site scope |
|---|---|---|
| `product` | product master (type, UOM, barcode, batch/serial flags, standard cost) | `site_id` NULL = shared |
| `uom`, `uom_conversion` | units of measure + per-product conversion | reference / product |
| `party`, `party_address`, `party_contact` | customers, suppliers, both | `site_id` NULL = shared |
| `warehouse`, `rack`, `storage_location`, `dock` | warehouse structure (racks, bins, supply/receiving/delivery/dispatch docks) | site-owned |
| `production_line`, `work_center` | manufacturing resources | site-owned |
| `work_calendar`, `work_calendar_day` | capacity planning calendar | site-owned |
| `organization_country` | currency, locale, timezone, fiscal-year start per country | per org |
| `tax_category`, `tax_rule`, `tax_rule_snapshot` | effective-dated tax rules + snapshot of the applied rule | per org |
| `numbering_sequence` | document numbering: prefix, padding, start, period type, counter | org-wide or site |

Every master table has `created_at`, `updated_at`, `row_version` (optimistic
locking) and `deleted_at` (soft delete).

## Scoping rules

- `SiteScopedSql.masterDataClause` — shared rows (`site_id IS NULL`) visible to
  every site of the organization; site-scoped users see only their sites.
- `SiteScopedSql.whereClause` — strict variant for transactional data.
- Deny-all scope (no site assigned) yields `1 = 0`, never "all rows".

## Numbering

```
SO-2026-000001      # org-wide sequence, fiscal year 2026, 6 digits
PO-2026-000001
INV-2026-00000001   # 8 digits
```

- Period type `NONE` → no period segment; `MONTHLY` → `2026-11`;
  `YEARLY` → fiscal year label (depends on `fiscal_year_start_month`).
- The counter restarts at `start_at` when the period changes.
- Concurrency: row lock + single transaction (see ADR-002). The integration
  test fires 200 parallel callers and asserts zero gaps and zero duplicates.

## Tax SPI

```java
public interface TaxRuleProvider {
    Optional<TaxRule> resolve(TaxQuery query);   // country + category + date
    String providerId();
}
```

`DatabaseTaxRuleProvider` is the default (effective-dated rules in the
database). A plugin can contribute another provider. `TaxService` computes the
lines and stores a snapshot of the applied rule.

## CSV import/export

Column formats (export order = import header):

- **products**: `code,name,product_type,uom_code,barcode,standard_cost,currency,track_inventory,batch_tracked,serial_tracked,description`
- **parties**: `code,name,party_type,tax_id,country_code,email,phone,credit_limit,payment_term_days,currency`

Import validates every row first (unknown type/UOM, duplicate code, bad
number, bad currency), then writes the rows that passed. With *dry-run* the
validation report is identical but nothing is written; rejected rows can be
downloaded as an error CSV.

## Tests

```bash
mvn -B verify   -Dultibiz.it.jdbcUrl=jdbc:mariadb://127.0.0.1:3311/ultibiz_test   -Dultibiz.it.user=ultibiz -Dultibiz.it.password=...
```

Integration tests (skipped without the URL) cover: site isolation, shared-row
visibility, organization isolation in one schema, soft delete, site-scoped vs
organization-wide sequences, period restart and the 200-way numbering
concurrency test.
