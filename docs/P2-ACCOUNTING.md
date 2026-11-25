# P2 — Accounting

## Schema (migration `V202611250003__p2_accounting_core.sql`)

| Table | Purpose |
|---|---|
| `account` | chart of accounts: hierarchical (`parent_id`), per organization, optional country, postable flag |
| `fiscal_period` | one row per organization + country + month, status `OPEN`/`CLOSED`/`REOPENED` |
| `journal_entry` | posted journal header: number, type, date, period, currency, rate, totals, source reference |
| `journal_line` | account + debit/credit + functional-currency amounts + tax snapshot fields |
| `fx_rate` | exchange rates (functional currency -> transaction currency), effective-dated |

The demo organization gets a standard chart of accounts (assets, liabilities,
equity, revenue, expenses with Indonesian accounts) and the fiscal periods
2025-12 .. 2026-12.

## Posting rules

- A journal must balance: `SUM(debit) = SUM(credit)`, every line has exactly
  one non-zero side, and the total is not zero.
- Every account must exist, be active and be postable.
- The posting date must fall inside an existing, open fiscal period.
- Header, lines, document number and audit entry are written in **one**
  transaction; the period row is locked with `SELECT … FOR UPDATE` as the first
  statement (see ADR-003).
- Posted journals are immutable. A correction is a reversal
  (`reverses_journal_id`), and the original is marked `REVERSED`.

## Fiscal periods

- `FiscalPeriodService.close` closes a period (audited).
- `FiscalPeriodService.reopen` needs the permission `accounting.period.reopen`
  (granted to the ADMIN role by the migration) and is audited. A reopened
  period accepts postings again but keeps its closure history.

## Tax

Two country packs behind the `TaxPack` SPI, plus a database-backed default:

| Pack | Country | Standard rate | Pricing | Rounding |
|---|---|---|---|---|
| `IndonesiaTaxPack` | ID | PPN 11 % (2 % reduced, exempt categories) | inclusive and exclusive | per line, HALF_UP, scale 2 |
| `EuVatTaxPack` | EU member states | VAT 20 % | inclusive and exclusive | per document, HALF_UP, scale 2 |
| `DefaultTaxPack` | any | from the effective-dated `tax_rule` table | both | as requested |

The fixture table that the packs must satisfy lives in
`TaxPackFixtureTest`:

```
pack      category        net        gross      inclusive  tax
ID PPN    PPN_STANDARD    100000.00  100000.00  no         11000.00
ID PPN    PPN_STANDARD    0          111000.00  yes        11000.00
ID PPN    PPN_REDUCED     100000.00  100000.00  no          2000.00
ID PPN    PPN_EXEMPT      100000.00  100000.00  no             0.00
EU VAT    VAT_STANDARD    100000.00  100000.00  no         20000.00
EU VAT    VAT_STANDARD    0          120000.00  yes        20000.00
```

## Multi-currency

`FxService` converts a transaction amount into the functional currency with the
rate of the document date (from `fx_rate` or from an `FxRateProvider`). The
journal stores both the transaction amounts and the functional-currency
amounts (`base_debit` / `base_credit`), so a later rate change never rewrites
the ledger.

## Screens

- `/accounting/accounts` — chart of accounts as a tree
- `/accounting/journals` — journal list with date filter and paging
- `/accounting/journals/new` — journal editor with live balance check
- `/accounting/journals/{id}` — journal detail with the reversal action
- `/accounting/periods` — fiscal period calendar with close / reopen
- `/accounting/trial-balance` — trial balance per account

## Tests

`AccountingIntegrationTest` (against a real MariaDB) covers: a balanced
journal is posted and stored balanced, an unbalanced journal is rejected
without writing anything, a line cannot carry both a debit and a credit,
50 randomly generated journals always balance, a reversal restores the
balances exactly, a closed period refuses postings, reopening needs the
permission, and the trial balance matches the posted lines.
