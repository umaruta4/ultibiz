-- UltiBiz P2b — accounts receivable, accounts payable, bank and matching.
--
-- Invoices/bills post a journal when they are issued; receipts/payments post
-- another journal when money moves; allocations link the two and drive the
-- OPEN / PARTIAL / PAID status. Nothing here is editable after posting.

CREATE TABLE ar_invoice (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    invoice_number  VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    sales_order_id  BIGINT UNSIGNED NULL,
    invoice_date    DATE            NOT NULL,
    due_date        DATE            NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    exchange_rate   DECIMAL(18,8)   NOT NULL DEFAULT 1.00000000,
    tax_inclusive   BOOLEAN         NOT NULL DEFAULT FALSE,
    subtotal        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    paid_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',  -- OPEN|PARTIAL|PAID|VOID
    journal_id      BIGINT UNSIGNED NOT NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ar_invoice_number (organization_id, invoice_number),
    KEY idx_ar_invoice_party (organization_id, party_id, status),
    KEY idx_ar_invoice_due (organization_id, due_date),
    CONSTRAINT fk_ar_invoice_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_ar_invoice_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_ar_invoice_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_ar_invoice_so FOREIGN KEY (sales_order_id) REFERENCES sales_order (id),
    CONSTRAINT fk_ar_invoice_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE ar_invoice_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    invoice_id     BIGINT UNSIGNED NOT NULL,
    line_no        INT             NOT NULL,
    product_id     BIGINT UNSIGNED NULL,
    description    VARCHAR(255)    NOT NULL,
    quantity       DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    unit_price     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    discount       DECIMAL(9,4)    NOT NULL DEFAULT 0.0000,
    tax_category_id BIGINT UNSIGNED NULL,
    tax_rate       DECIMAL(9,6)    NULL,
    net_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_amount   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ar_invoice_line (invoice_id, line_no),
    CONSTRAINT fk_ar_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES ar_invoice (id),
    CONSTRAINT fk_ar_invoice_line_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;

CREATE TABLE bank_account (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    bank_name       VARCHAR(255)    NULL,
    account_number  VARCHAR(64)     NULL,
    gl_account_id   BIGINT UNSIGNED NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bank_account_org_code (organization_id, code),
    CONSTRAINT fk_bank_account_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_bank_account_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_bank_account_gl FOREIGN KEY (gl_account_id) REFERENCES account (id)
) ENGINE = InnoDB;

CREATE TABLE ar_receipt (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    receipt_number  VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    receipt_date    DATE            NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    method          VARCHAR(24)     NOT NULL DEFAULT 'TRANSFER',  -- CASH|BANK|TRANSFER|CHEQUE
    bank_account_id BIGINT UNSIGNED NULL,
    journal_id      BIGINT UNSIGNED NOT NULL,
    reference       VARCHAR(64)     NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ar_receipt_number (organization_id, receipt_number),
    KEY idx_ar_receipt_party (organization_id, party_id),
    CONSTRAINT fk_ar_receipt_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_ar_receipt_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_ar_receipt_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_ar_receipt_bank FOREIGN KEY (bank_account_id) REFERENCES bank_account (id),
    CONSTRAINT fk_ar_receipt_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE ar_allocation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    receipt_id      BIGINT UNSIGNED NOT NULL,
    invoice_id      BIGINT UNSIGNED NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ar_allocation (receipt_id, invoice_id),
    KEY idx_ar_allocation_invoice (invoice_id),
    CONSTRAINT fk_ar_allocation_receipt FOREIGN KEY (receipt_id) REFERENCES ar_receipt (id),
    CONSTRAINT fk_ar_allocation_invoice FOREIGN KEY (invoice_id) REFERENCES ar_invoice (id)
) ENGINE = InnoDB;

CREATE TABLE ap_bill (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    bill_number     VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    purchase_order_id BIGINT UNSIGNED NULL,
    bill_date       DATE            NOT NULL,
    due_date        DATE            NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    subtotal        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    paid_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',  -- OPEN|PARTIAL|PAID|VOID
    journal_id      BIGINT UNSIGNED NOT NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_bill_number (organization_id, bill_number),
    KEY idx_ap_bill_party (organization_id, party_id, status),
    KEY idx_ap_bill_due (organization_id, due_date),
    CONSTRAINT fk_ap_bill_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_ap_bill_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_ap_bill_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_ap_bill_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_ap_bill_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE ap_bill_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bill_id        BIGINT UNSIGNED NOT NULL,
    line_no        INT             NOT NULL,
    product_id     BIGINT UNSIGNED NULL,
    description    VARCHAR(255)    NOT NULL,
    quantity       DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    unit_price     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_category_id BIGINT UNSIGNED NULL,
    tax_rate       DECIMAL(9,6)    NULL,
    net_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_amount   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_bill_line (bill_id, line_no),
    CONSTRAINT fk_ap_bill_line_bill FOREIGN KEY (bill_id) REFERENCES ap_bill (id),
    CONSTRAINT fk_ap_bill_line_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;

CREATE TABLE ap_payment (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    payment_number  VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    payment_date    DATE            NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    method          VARCHAR(24)     NOT NULL DEFAULT 'TRANSFER',
    bank_account_id BIGINT UNSIGNED NULL,
    journal_id      BIGINT UNSIGNED NOT NULL,
    reference       VARCHAR(64)     NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_payment_number (organization_id, payment_number),
    KEY idx_ap_payment_party (organization_id, party_id),
    CONSTRAINT fk_ap_payment_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_ap_payment_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_ap_payment_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_ap_payment_bank FOREIGN KEY (bank_account_id) REFERENCES bank_account (id),
    CONSTRAINT fk_ap_payment_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE ap_allocation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    payment_id      BIGINT UNSIGNED NOT NULL,
    bill_id         BIGINT UNSIGNED NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ap_allocation (payment_id, bill_id),
    KEY idx_ap_allocation_bill (bill_id),
    CONSTRAINT fk_ap_allocation_payment FOREIGN KEY (payment_id) REFERENCES ap_payment (id),
    CONSTRAINT fk_ap_allocation_bill FOREIGN KEY (bill_id) REFERENCES ap_bill (id)
) ENGINE = InnoDB;

CREATE TABLE bank_statement_line (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bank_account_id BIGINT UNSIGNED NOT NULL,
    import_batch    CHAR(36)        NOT NULL,
    statement_date  DATE            NOT NULL,
    description     VARCHAR(255)    NOT NULL,
    reference       VARCHAR(64)     NULL,
    amount          DECIMAL(18,2)   NOT NULL,      -- signed: + in, - out
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    reconciled      BOOLEAN         NOT NULL DEFAULT FALSE,
    matched_movement_id BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_bank_statement_account (bank_account_id, statement_date),
    KEY idx_bank_statement_batch (import_batch),
    CONSTRAINT fk_bank_statement_bank FOREIGN KEY (bank_account_id) REFERENCES bank_account (id)
) ENGINE = InnoDB;

CREATE TABLE match_rule (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    rule_type       VARCHAR(32)     NOT NULL,   -- THREE_WAY_MATCH | RECEIPT_MATCH | PAYMENT_MATCH
    tolerance_percent DECIMAL(9,4)  NOT NULL DEFAULT 0.0000,  -- 0.05 = 5%
    tolerance_amount  DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_match_rule (organization_id, rule_type),
    CONSTRAINT fk_match_rule_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- Flyway runs every migration on its own connection, so the demo organization
-- has to be resolved inside this script.
SET @org := (SELECT id FROM organization WHERE code = 'ACME');

-- ---------------------------------------------------------------------------
-- Document numbering + a default bank account + a default tolerance
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
    padding, start_at, counter, period_type) VALUES
    (@org, NULL, 'INV',  'Sales Invoice',      'INV-',  '', 8, 1, 0, 'YEARLY'),
    (@org, NULL, 'BILL', 'Supplier Bill',      'BILL-', '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'RCPT', 'Customer Receipt',   'RCPT-', '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'PAY',  'Supplier Payment',   'PAY-',  '', 6, 1, 0, 'YEARLY');

SET @jkt := (SELECT id FROM site WHERE code = 'JKT1');
SET @bank_gl := (SELECT id FROM account WHERE code = '1110');

INSERT INTO bank_account (organization_id, site_id, code, name, bank_name, account_number,
    gl_account_id, currency) VALUES
    (@org, @jkt, 'BANK-BCA', 'Bank BCA Main', 'BCA', '123-456-7890', @bank_gl, 'IDR'),
    (@org, @jkt, 'CASH-01',  'Cash on Hand',  NULL,  NULL, @bank_gl, 'IDR');

INSERT IGNORE INTO match_rule (organization_id, rule_type, tolerance_percent, tolerance_amount) VALUES
    (@org, 'THREE_WAY_MATCH', 0.0200, 50000.00),
    (@org, 'RECEIPT_MATCH',   0.0000, 5000.00),
    (@org, 'PAYMENT_MATCH',   0.0000, 5000.00);
