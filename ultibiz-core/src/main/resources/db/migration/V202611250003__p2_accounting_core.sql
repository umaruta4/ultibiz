-- UltiBiz P2 — Accounting core: chart of accounts, fiscal periods, journal,
-- journal lines, FX rates. One database, org-scoped like every other module.
--
-- Rules enforced by the code (not by the database):
--   * a journal is balanced by construction (debits == credits) before it is written
--   * a posted journal is immutable; corrections are reversals
--   * nothing posts into a closed fiscal period

CREATE TABLE account (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NULL,        -- NULL = usable in every country
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    account_type    VARCHAR(16)     NOT NULL,     -- ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE
    parent_id       BIGINT UNSIGNED NULL,
    normal_balance  VARCHAR(8)      NOT NULL DEFAULT 'DEBIT',   -- DEBIT|CREDIT
    currency        CHAR(3)         NULL,        -- NULL = functional currency
    is_postable     BOOLEAN         NOT NULL DEFAULT TRUE,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    deleted_at      DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_org_code (organization_id, code),
    KEY idx_account_org_type (organization_id, account_type),
    KEY idx_account_parent (parent_id),
    CONSTRAINT fk_account_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_account_parent FOREIGN KEY (parent_id) REFERENCES account (id),
    CONSTRAINT fk_account_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

CREATE TABLE fiscal_period (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    fiscal_year     INT             NOT NULL,
    fiscal_month    TINYINT         NOT NULL,
    period_start    DATE            NOT NULL,
    period_end      DATE            NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',    -- OPEN|CLOSED|REOPENED
    closed_at       DATETIME(6)     NULL,
    closed_by       VARCHAR(128)    NULL,
    reopened_at     DATETIME(6)     NULL,
    reopened_by     VARCHAR(128)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_period_org_country (organization_id, country_code, fiscal_year, fiscal_month),
    KEY idx_period_lookup (organization_id, country_code, period_start),
    CONSTRAINT fk_period_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_period_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

CREATE TABLE journal_entry (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT UNSIGNED NOT NULL,
    site_id           BIGINT UNSIGNED NULL,
    country_code      CHAR(2)         NOT NULL,
    journal_number    VARCHAR(64)     NOT NULL,
    journal_type      VARCHAR(32)     NOT NULL,    -- GENERAL|SALES|PURCHASE|RECEIPT|PAYMENT|REVERSAL|OPENING|CLOSING|FX_REVALUATION
    posting_date      DATE            NOT NULL,
    fiscal_period_id  BIGINT UNSIGNED NOT NULL,
    currency          CHAR(3)         NOT NULL,
    exchange_rate     DECIMAL(18,8)   NOT NULL DEFAULT 1.00000000,
    reference         VARCHAR(64)     NULL,
    source_type       VARCHAR(64)     NULL,
    source_id         BIGINT UNSIGNED NULL,
    reverses_journal_id BIGINT UNSIGNED NULL,
    description       VARCHAR(512)    NULL,
    total_debit       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_credit      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    status            VARCHAR(16)     NOT NULL DEFAULT 'POSTED',  -- POSTED|REVERSED
    posted_by         VARCHAR(128)    NOT NULL,
    posted_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    correlation_id    CHAR(36)        NULL,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version       BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_journal_org_number (organization_id, journal_number),
    KEY idx_journal_period (fiscal_period_id),
    KEY idx_journal_date (organization_id, posting_date),
    KEY idx_journal_source (source_type, source_id),
    CONSTRAINT fk_journal_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_journal_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_journal_period FOREIGN KEY (fiscal_period_id) REFERENCES fiscal_period (id),
    CONSTRAINT fk_journal_reverses FOREIGN KEY (reverses_journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE journal_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    journal_id     BIGINT UNSIGNED NOT NULL,
    line_no        INT             NOT NULL,
    account_id     BIGINT UNSIGNED NOT NULL,
    party_id       BIGINT UNSIGNED NULL,
    site_id        BIGINT UNSIGNED NULL,
    description    VARCHAR(512)    NULL,
    debit          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    credit         DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency       CHAR(3)         NOT NULL,
    exchange_rate  DECIMAL(18,8)   NOT NULL DEFAULT 1.00000000,
    base_debit     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    base_credit    DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_category_id BIGINT UNSIGNED NULL,
    tax_rate       DECIMAL(9,6)    NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_journal_line_no (journal_id, line_no),
    KEY idx_journal_line_account (account_id),
    KEY idx_journal_line_party (party_id),
    CONSTRAINT fk_journal_line_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_journal_line_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_journal_line_party FOREIGN KEY (party_id) REFERENCES party (id)
) ENGINE = InnoDB;

CREATE TABLE fx_rate (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    base_currency   CHAR(3)         NOT NULL,
    quote_currency  CHAR(3)         NOT NULL,
    rate            DECIMAL(18,8)   NOT NULL,
    effective_date  DATE            NOT NULL,
    source          VARCHAR(32)     NOT NULL DEFAULT 'manual',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_fx_rate (organization_id, base_currency, quote_currency, effective_date),
    KEY idx_fx_rate_lookup (organization_id, base_currency, quote_currency, effective_date),
    CONSTRAINT fk_fx_rate_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: chart of accounts for the demo organization
-- ---------------------------------------------------------------------------

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

INSERT INTO account (organization_id, country_code, code, name, account_type, parent_id, normal_balance, currency) VALUES
    (@org, 'ID', '1000', 'Assets',                    'ASSET',     NULL, 'DEBIT',  NULL),
    (@org, 'ID', '1100', 'Cash on Hand',              'ASSET',     (SELECT id FROM account WHERE code='1000' LIMIT 1), 'DEBIT', 'IDR'),
    (@org, 'ID', '1110', 'Bank Account',              'ASSET',     (SELECT id FROM account WHERE code='1000' LIMIT 1), 'DEBIT', 'IDR'),
    (@org, 'ID', '1200', 'Accounts Receivable',       'ASSET',     (SELECT id FROM account WHERE code='1000' LIMIT 1), 'DEBIT', 'IDR'),
    (@org, 'ID', '1300', 'Inventory',                 'ASSET',     (SELECT id FROM account WHERE code='1000' LIMIT 1), 'DEBIT', 'IDR'),
    (@org, 'ID', '1400', 'Fixed Assets',              'ASSET',     (SELECT id FROM account WHERE code='1000' LIMIT 1), 'DEBIT', 'IDR'),
    (@org, 'ID', '2000', 'Liabilities',               'LIABILITY', NULL, 'CREDIT', NULL),
    (@org, 'ID', '2100', 'Accounts Payable',          'LIABILITY', (SELECT id FROM account WHERE code='2000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '2110', 'Tax Payable - PPN',         'LIABILITY', (SELECT id FROM account WHERE code='2000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '2200', 'Loans Payable',             'LIABILITY', (SELECT id FROM account WHERE code='2000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '3000', 'Equity',                    'EQUITY',    NULL, 'CREDIT', NULL),
    (@org, 'ID', '3100', 'Capital',                   'EQUITY',    (SELECT id FROM account WHERE code='3000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '3200', 'Retained Earnings',         'EQUITY',    (SELECT id FROM account WHERE code='3000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '4000', 'Revenue',                   'REVENUE',   NULL, 'CREDIT', NULL),
    (@org, 'ID', '4100', 'Sales Revenue',             'REVENUE',   (SELECT id FROM account WHERE code='4000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '4200', 'Other Income',              'REVENUE',   (SELECT id FROM account WHERE code='4000' LIMIT 1), 'CREDIT', 'IDR'),
    (@org, 'ID', '5000', 'Expenses',                  'EXPENSE',   NULL, 'DEBIT',  NULL),
    (@org, 'ID', '5100', 'Cost of Goods Sold',        'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR'),
    (@org, 'ID', '5200', 'Salaries',                  'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR'),
    (@org, 'ID', '5300', 'Rent',                      'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR'),
    (@org, 'ID', '5400', 'Utilities',                 'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR'),
    (@org, 'ID', '5500', 'Freight &amp; Delivery',       'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR'),
    (@org, 'ID', '5600', 'FX Gain/Loss',              'EXPENSE',   (SELECT id FROM account WHERE code='5000' LIMIT 1), 'DEBIT',  'IDR');

-- Document numbering for journals
INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
    padding, start_at, counter, period_type) VALUES
    (@org, NULL, 'JV',  'Journal Voucher',  'JV-',  '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'REV', 'Journal Reversal',  'REV-', '', 6, 1, 0, 'YEARLY');

-- Fiscal periods: 2025-12 .. 2026-12 for ID. Past periods stay open so the
-- application can demonstrate closing; the demo closes them from the UI.
INSERT INTO fiscal_period (organization_id, country_code, fiscal_year, fiscal_month, period_start, period_end, status)
SELECT @org, 'ID', 2025, 12, '2025-12-01', '2025-12-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 1,  '2026-01-01', '2026-01-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 2,  '2026-02-01', '2026-02-28', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 3,  '2026-03-01', '2026-03-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 4,  '2026-04-01', '2026-04-30', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 5,  '2026-05-01', '2026-05-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 6,  '2026-06-01', '2026-06-30', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 7,  '2026-07-01', '2026-07-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 8,  '2026-08-01', '2026-08-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 9,  '2026-09-01', '2026-09-30', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 10, '2026-10-01', '2026-10-31', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 11, '2026-11-01', '2026-11-30', 'OPEN'
UNION ALL SELECT @org, 'ID', 2026, 12, '2026-12-01', '2026-12-31', 'OPEN';

-- One FX rate so the multi-currency path has data to work with
INSERT INTO fx_rate (organization_id, base_currency, quote_currency, rate, effective_date, source)
VALUES (@org, 'IDR', 'USD', 0.00006100, '2026-01-01', 'manual');

-- Permission: reopening a closed fiscal period (audited, admin only)
INSERT IGNORE INTO permission (code, description) VALUES
    ('accounting.period.reopen', 'Reopen a closed fiscal period');

INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p
 WHERE r.code = 'ADMIN' AND p.code = 'accounting.period.reopen';
