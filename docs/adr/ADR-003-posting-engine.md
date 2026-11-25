# ADR-003 — The posting engine: one transaction, immutable postings, period locks

- **Status:** accepted
- **Date:** 2026-11-25
- **Phase:** P2

## Context

An ERP is only as trustworthy as its ledger. The failure modes that destroy
trust are: a journal that is posted without balancing, a journal that is edited
after the fact, a posting that lands in a period that was closed meanwhile, and
a half-written journal (header without lines) after a crash.

## Decision

### 1. Balanced by construction

`JournalService.post` refuses to write anything unless the debit total equals
the credit total and every line has exactly one non-zero side. The check
happens **before** the transaction opens, so an unbalanced journal never even
reaches the database.

### 2. One transaction for the whole posting

Inside a single transaction (READ COMMITTED):

1. `SELECT … FOR UPDATE` on the fiscal period row — the first statement of the
   transaction, so the lock is taken on the current row version.
2. Re-check that the period is still open (it may have been closed while we
   waited).
3. Take the document number from the numbering engine (its own row lock, in
   the same transaction).
4. Insert the header, then the lines.
5. Write the audit record.

A crash at any point rolls the whole posting back: no journal without lines,
no consumed document number, no audit entry without a journal.

### 3. Posted journals are immutable

There is no update path for `journal_entry` or `journal_line`. A correction is
a **reversal**: a new journal with the mirrored debit/credit lines, linked
through `reverses_journal_id`, after which the original is marked `REVERSED`.
Both journals stay visible, so the history is provable.

### 4. Closed periods reject postings

`fiscal_period.status` is `OPEN`, `CLOSED` or `REOPENED`. A posting into a
closed period is refused. Reopening needs the permission
`accounting.period.reopen` and is audited (`PERIOD_REOPEN`), because a period
that was reopened keeps its closure history in `closed_at` / `reopened_at`.

### 5. Trial balance filters the journals, not the accounts

The trial balance aggregates the journal lines **inside** the date range and
then joins them to the accounts. The first implementation filtered the account
join instead, which counted lines of journals outside the period and produced an
unbalanced balance — caught by the integration test.

## Consequences

- A posting is a serialised operation per fiscal period (one row lock). For
  document volumes of an ERP this is irrelevant; for high-frequency postings
  (e.g. stock movements per scan) P3 will introduce a lock-free path.
- The reversal of a journal is dated when the reversal is posted, not the
  original date. A trial balance over a date range can therefore be unbalanced
  when the original and its reversal fall in different periods — which is
  correct, and the year-end closing handles it.
- Money is `DECIMAL(18,2)` in the ledger, `DECIMAL(18,8)` for exchange rates.
  No float ever touches a balance.
