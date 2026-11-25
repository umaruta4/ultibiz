-- UltiBiz P1 — master data, multi-site structures, country/tax config, numbering
-- MariaDB 11.8+ / InnoDB / utf8mb4
--
-- Convention (P1):
--   * every master table carries organization_id + site_id
--   * site_id NULL  = organization-wide (shared) master record
--     site_id SET   = owned by that site
--   * soft delete via deleted_at, optimistic locking via row_version
--   * money/quantity = DECIMAL (never float)

-- ---------------------------------------------------------------------------
-- Units of measure
-- ---------------------------------------------------------------------------

CREATE TABLE uom (
    code            VARCHAR(16)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    decimal_places  TINYINT      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE tax_category (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NULL,           -- NULL = global catalogue entry
    org_key       BIGINT UNSIGNED AS (IFNULL(organization_id, 0)) STORED,
    code         VARCHAR(64)     NOT NULL,
    name         VARCHAR(255)    NOT NULL,
    tax_type     VARCHAR(32)     NOT NULL DEFAULT 'VAT',  -- VAT | GST | WITHHOLDING | EXCISE | NONE
    is_sales     BOOLEAN         NOT NULL DEFAULT TRUE,
    is_purchase  BOOLEAN         NOT NULL DEFAULT TRUE,
    is_active    BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tax_category_org_code (org_key, code),
    CONSTRAINT fk_tax_category_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------------
-- Product master
-- ---------------------------------------------------------------------------

CREATE TABLE product (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT UNSIGNED NOT NULL,
    site_id          BIGINT UNSIGNED NULL,          -- NULL = shared org-wide
    site_key         BIGINT UNSIGNED AS (IFNULL(site_id, 0)) STORED,
    code             VARCHAR(64)     NOT NULL,
    name             VARCHAR(255)    NOT NULL,
    product_type     VARCHAR(32)     NOT NULL DEFAULT 'GOODS',   -- GOODS | SERVICE | KIT
    uom_code         VARCHAR(16)     NOT NULL,
    barcode          VARCHAR(64)     NULL,
    batch_tracked    BOOLEAN         NOT NULL DEFAULT FALSE,
    serial_tracked   BOOLEAN         NOT NULL DEFAULT FALSE,
    track_inventory  BOOLEAN         NOT NULL DEFAULT TRUE,
    standard_cost    DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
    currency         CHAR(3)         NOT NULL DEFAULT 'IDR',
    tax_category_id  BIGINT UNSIGNED NULL,
    description      TEXT            NULL,
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version      BIGINT          NOT NULL DEFAULT 0,
    deleted_at       DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_org_site_code (organization_id, site_key, code),
    KEY idx_product_org (organization_id),
    KEY idx_product_name (organization_id, name),
    KEY idx_product_barcode (organization_id, barcode),
    CONSTRAINT fk_product_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_product_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_product_uom FOREIGN KEY (uom_code) REFERENCES uom (code),
    CONSTRAINT fk_product_taxcat FOREIGN KEY (tax_category_id) REFERENCES tax_category (id)
) ENGINE = InnoDB;

-- MySQL treats NULL as distinct in unique indexes; site_key normalises
-- "shared" (NULL site) to 0 so the uniqueness constraint really holds.

-- ---------------------------------------------------------------------------
-- UOM conversion (per product)
-- ---------------------------------------------------------------------------

CREATE TABLE uom_conversion (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id   BIGINT UNSIGNED NOT NULL,
    from_uom     VARCHAR(16)  NOT NULL,
    to_uom       VARCHAR(16)  NOT NULL,
    numerator    DECIMAL(18,6) NOT NULL DEFAULT 1.000000,
    denominator  DECIMAL(18,6) NOT NULL DEFAULT 1.000000,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_uom_conv (product_id, from_uom, to_uom),
    CONSTRAINT fk_uom_conv_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_uom_conv_from FOREIGN KEY (from_uom) REFERENCES uom (code),
    CONSTRAINT fk_uom_conv_to FOREIGN KEY (to_uom) REFERENCES uom (code)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Party master (customer / supplier / both)
-- ---------------------------------------------------------------------------

CREATE TABLE party (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT UNSIGNED NOT NULL,
    site_id           BIGINT UNSIGNED NULL,
    site_key          BIGINT UNSIGNED AS (IFNULL(site_id, 0)) STORED,
    code              VARCHAR(64)     NOT NULL,
    name              VARCHAR(255)    NOT NULL,
    party_type        VARCHAR(32)     NOT NULL DEFAULT 'BOTH',  -- CUSTOMER | SUPPLIER | BOTH
    tax_id            VARCHAR(64)     NULL,
    country_code      CHAR(2)         NOT NULL DEFAULT 'ID',
    email             VARCHAR(255)    NULL,
    phone             VARCHAR(64)     NULL,
    credit_limit      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    payment_term_days INT             NOT NULL DEFAULT 30,
    currency          CHAR(3)         NOT NULL DEFAULT 'IDR',
    is_active         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version       BIGINT          NOT NULL DEFAULT 0,
    deleted_at        DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_party_org_site_code (organization_id, site_key, code),
    KEY idx_party_org (organization_id),
    KEY idx_party_name (organization_id, name),
    KEY idx_party_taxid (organization_id, tax_id),
    CONSTRAINT fk_party_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_party_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_party_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

-- site_key normalises the shared (NULL site) case for the unique key
CREATE TABLE party_address (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    party_id      BIGINT UNSIGNED NOT NULL,
    address_type  VARCHAR(32)     NOT NULL DEFAULT 'OTHER',  -- BILLING | SHIPPING | OFFICE | OTHER
    address_line  VARCHAR(512)    NOT NULL,
    city          VARCHAR(128)    NULL,
    postal_code   VARCHAR(32)     NULL,
    country_code  CHAR(2)         NOT NULL DEFAULT 'ID',
    is_primary    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_party_address_party (party_id),
    CONSTRAINT fk_party_address_party FOREIGN KEY (party_id) REFERENCES party (id)
) ENGINE = InnoDB;

CREATE TABLE party_contact (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    party_id      BIGINT UNSIGNED NOT NULL,
    contact_name  VARCHAR(255)    NOT NULL,
    contact_role  VARCHAR(128)    NULL,
    email         VARCHAR(255)    NULL,
    phone         VARCHAR(64)     NULL,
    is_primary    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_party_contact_party (party_id),
    CONSTRAINT fk_party_contact_party FOREIGN KEY (party_id) REFERENCES party (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Warehouse structure: warehouse -> rack -> bin, plus docks
-- ---------------------------------------------------------------------------

CREATE TABLE warehouse (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    warehouse_type  VARCHAR(32)     NOT NULL DEFAULT 'STORAGE', -- STORAGE | PRODUCTION | DISTRIBUTION | QUARANTINE
    country_code    CHAR(2)         NOT NULL DEFAULT 'ID',
    address         VARCHAR(512)    NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    deleted_at      DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_warehouse_org_site_code (organization_id, site_id, code),
    KEY idx_warehouse_site (site_id),
    CONSTRAINT fk_warehouse_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_warehouse_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_warehouse_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

CREATE TABLE rack (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    warehouse_id  BIGINT UNSIGNED NOT NULL,
    code          VARCHAR(64)     NOT NULL,
    name          VARCHAR(255)    NOT NULL,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_rack_wh_code (warehouse_id, code),
    CONSTRAINT fk_rack_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
) ENGINE = InnoDB;

CREATE TABLE storage_location (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    warehouse_id  BIGINT UNSIGNED NOT NULL,
    rack_id       BIGINT UNSIGNED NULL,
    code          VARCHAR(64)     NOT NULL,
    name          VARCHAR(255)    NOT NULL,
    location_type VARCHAR(32)     NOT NULL DEFAULT 'BIN',  -- BIN | PALLET | FLOOR | STAGING
    capacity      DECIMAL(18,3)   NULL,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_loc_wh_code (warehouse_id, code),
    KEY idx_loc_rack (rack_id),
    CONSTRAINT fk_loc_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_loc_rack FOREIGN KEY (rack_id) REFERENCES rack (id)
) ENGINE = InnoDB;

CREATE TABLE dock (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    warehouse_id  BIGINT UNSIGNED NOT NULL,
    code          VARCHAR(64)     NOT NULL,
    name          VARCHAR(255)    NOT NULL,
    dock_type     VARCHAR(32)     NOT NULL DEFAULT 'DELIVERY', -- SUPPLY | RECEIVING | DELIVERY | DISPATCH
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dock_wh_code (warehouse_id, code),
    CONSTRAINT fk_dock_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Manufacturing resources
-- ---------------------------------------------------------------------------

CREATE TABLE work_center (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id        BIGINT UNSIGNED NOT NULL,
    site_id                BIGINT UNSIGNED NOT NULL,
    code                   VARCHAR(64)     NOT NULL,
    name                   VARCHAR(255)    NOT NULL,
    work_center_type       VARCHAR(32)     NOT NULL DEFAULT 'MACHINE', -- MACHINE | WORKSTATION | LINE | VENDOR
    capacity_hours_per_day DECIMAL(8,2)    NOT NULL DEFAULT 8.00,
    is_active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version            BIGINT          NOT NULL DEFAULT 0,
    deleted_at             DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_work_center_org_site_code (organization_id, site_id, code),
    CONSTRAINT fk_work_center_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_work_center_site FOREIGN KEY (site_id) REFERENCES site (id)
) ENGINE = InnoDB;

CREATE TABLE production_line (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id    BIGINT UNSIGNED NOT NULL,
    site_id            BIGINT UNSIGNED NOT NULL,
    code               VARCHAR(64)     NOT NULL,
    name               VARCHAR(255)    NOT NULL,
    warehouse_id       BIGINT UNSIGNED NULL,
    work_center_id     BIGINT UNSIGNED NULL,
    capacity_per_day   DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
    is_active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version        BIGINT          NOT NULL DEFAULT 0,
    deleted_at         DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prod_line_org_site_code (organization_id, site_id, code),
    CONSTRAINT fk_prod_line_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_prod_line_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_prod_line_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_prod_line_wc FOREIGN KEY (work_center_id) REFERENCES work_center (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Work calendar (per site, drives capacity planning in P3)
-- ---------------------------------------------------------------------------

CREATE TABLE work_calendar (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    timezone        VARCHAR(64)     NOT NULL DEFAULT 'Asia/Jakarta',
    description     VARCHAR(512)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_calendar_org_site_code (organization_id, site_id, code),
    CONSTRAINT fk_calendar_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_calendar_site FOREIGN KEY (site_id) REFERENCES site (id)
) ENGINE = InnoDB;

CREATE TABLE work_calendar_day (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    calendar_id    BIGINT UNSIGNED NOT NULL,
    work_date      DATE            NOT NULL,
    is_working     BOOLEAN         NOT NULL DEFAULT TRUE,
    working_hours  DECIMAL(5,2)    NOT NULL DEFAULT 8.00,
    PRIMARY KEY (id),
    UNIQUE KEY uq_calendar_day (calendar_id, work_date),
    CONSTRAINT fk_calendar_day_cal FOREIGN KEY (calendar_id) REFERENCES work_calendar (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Country configuration per organization (multi-national)
-- ---------------------------------------------------------------------------

CREATE TABLE organization_country (
    organization_id       BIGINT UNSIGNED NOT NULL,
    country_code          CHAR(2)         NOT NULL,
    is_enabled            BOOLEAN         NOT NULL DEFAULT TRUE,
    currency              CHAR(3)         NOT NULL,
    locale                VARCHAR(10)     NOT NULL DEFAULT 'en',
    timezone              VARCHAR(64)     NOT NULL DEFAULT 'Asia/Jakarta',
    fiscal_year_start_month TINYINT      NOT NULL DEFAULT 1,
    fiscal_year_start_day   TINYINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (organization_id, country_code),
    CONSTRAINT fk_org_country_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_org_country_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Tax: categories + effective-dated rules (SPI reads these)
-- ---------------------------------------------------------------------------

-- Snapshot of the rule actually applied on a computed tax line: a later rate
-- change must never rewrite historical documents.
CREATE TABLE tax_rule_snapshot (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    tax_rule_id     BIGINT UNSIGNED NULL,
    category_code   VARCHAR(64)     NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    rate            DECIMAL(9,6)    NOT NULL,
    effective_from  DATE            NOT NULL,
    captured_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_tax_snapshot_org (organization_id, captured_at),
    CONSTRAINT fk_tax_snapshot_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE tax_rule (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    tax_category_id BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    rate            DECIMAL(9,6)    NOT NULL,
    effective_from  DATE            NOT NULL,
    effective_to    DATE            NULL,
    priority        INT             NOT NULL DEFAULT 100,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tax_rule_lookup (organization_id, country_code, tax_category_id, effective_from),
    CONSTRAINT fk_tax_rule_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_tax_rule_cat FOREIGN KEY (tax_category_id) REFERENCES tax_category (id),
    CONSTRAINT fk_tax_rule_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Numbering engine: one sequence per document type + scope
--   site_key = 0 means the sequence is organization-wide (site_id NULL)
-- ---------------------------------------------------------------------------

CREATE TABLE numbering_sequence (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT UNSIGNED NOT NULL,
    site_id          BIGINT UNSIGNED NULL,
    site_key         BIGINT UNSIGNED AS (IFNULL(site_id, 0)) STORED,
    document_type    VARCHAR(64)     NOT NULL,
    name             VARCHAR(255)    NOT NULL,
    prefix           VARCHAR(32)     NOT NULL DEFAULT '',
    suffix           VARCHAR(32)     NOT NULL DEFAULT '',
    padding          INT             NOT NULL DEFAULT 6,
    start_at         BIGINT          NOT NULL DEFAULT 1,
    counter          BIGINT          NOT NULL DEFAULT 0,
    period_type      VARCHAR(16)     NOT NULL DEFAULT 'NONE',  -- NONE | MONTHLY | YEARLY
    fiscal_year_start_month TINYINT NOT NULL DEFAULT 1,
    last_period_key  VARCHAR(16)     NULL,
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version      BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_numbering_scope (organization_id, site_key, document_type),
    CONSTRAINT fk_numbering_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_numbering_site FOREIGN KEY (site_id) REFERENCES site (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------------

INSERT INTO uom (code, name, decimal_places) VALUES
    ('PCS',   'Piece',            0),
    ('BOX',   'Box',              0),
    ('KG',    'Kilogram',         3),
    ('L',     'Litre',            3),
    ('M',     'Metre',            2),
    ('HR',    'Hour',             2),
    ('PALLET','Pallet',           0);

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

INSERT INTO organization_country (organization_id, country_code, is_enabled, currency, locale, timezone,
                                  fiscal_year_start_month, fiscal_year_start_day)
VALUES (@org, 'ID', TRUE, 'IDR', 'id', 'Asia/Jakarta', 1, 1),
       (@org, 'US', TRUE, 'USD', 'en', 'America/New_York', 1, 1);

INSERT INTO tax_category (organization_id, code, name, tax_type, is_sales, is_purchase) VALUES
    (NULL, 'PPN_STANDARD', 'VAT 11% (standard)',   'VAT', TRUE,  TRUE),
    (NULL, 'PPN_REDUCED',  'VAT 2% (reduced)',     'VAT', TRUE,  TRUE),
    (NULL, 'PPN_EXEMPT',   'VAT exempt',           'VAT', TRUE,  TRUE),
    (NULL, 'PPN_0',        'Non-taxable',          'NONE',TRUE,  TRUE),
    (@org,  'WITHHOLDING',  'Withholding tax 2%',   'WITHHOLDING', FALSE, TRUE);

INSERT INTO tax_rule (organization_id, tax_category_id, country_code, name, rate, effective_from, effective_to, priority)
SELECT @org, c.id, 'ID', 'VAT 11% standard', 0.110000, '2020-01-01', NULL, 10
FROM tax_category c WHERE c.code = 'PPN_STANDARD';

INSERT INTO tax_rule (organization_id, tax_category_id, country_code, name, rate, effective_from, effective_to, priority)
SELECT @org, c.id, 'ID', 'VAT 2% reduced', 0.020000, '2020-01-01', NULL, 10
FROM tax_category c WHERE c.code = 'PPN_REDUCED';

SET @cat_std := (SELECT id FROM tax_category WHERE code = 'PPN_STANDARD' AND organization_id IS NULL);

INSERT INTO product (organization_id, site_id, code, name, product_type, uom_code, track_inventory,
                     standard_cost, currency, tax_category_id, description)
VALUES (@org, NULL, 'BRG-0001', 'Bearing 6204-2RS',   'GOODS', 'PCS', TRUE, 45000.0000, 'IDR', @cat_std, 'Sealed ball bearing'),
       (@org, NULL, 'BRG-0002', 'Bearing 6205-2RS',   'GOODS', 'PCS', TRUE, 52000.0000, 'IDR', @cat_std, 'Sealed ball bearing'),
       (@org, NULL, 'SVC-MAINT', 'Maintenance service (hour)', 'SERVICE', 'HR', FALSE, 250000.0000, 'IDR', NULL, 'Billable service'),
       (@org, NULL, 'PALLET-EUR', 'Euro pallet',    'GOODS', 'PCS', TRUE, 65000.0000, 'IDR', @cat_std, 'Reusable transport pallet');

INSERT INTO party (organization_id, site_id, code, name, party_type, tax_id, country_code,
                   email, phone, credit_limit, payment_term_days, currency)
VALUES (@org, NULL, 'CUST-0001', 'PT Nusantara Manufaktur', 'BOTH', '01.234.567.8-901.000', 'ID',
        'purchasing@nusantara.example', '+62 21 5550100', 500000000.00, 45, 'IDR'),
       (@org, NULL, 'CUST-0002', 'PT Samudera Distribusi', 'CUSTOMER', '02.345.678.9-002.000', 'ID',
        'order@samudera.example', '+62 31 5550200', 250000000.00, 30, 'IDR'),
       (@org, NULL, 'SUPP-0001', 'CV Baja Nusantara', 'SUPPLIER', '03.456.789.0-003.000', 'ID',
        'sales@baja.example', '+62 22 5550300', 0.00, 30, 'IDR');

SET @site_jkt := (SELECT id FROM site WHERE code = 'JKT1');

INSERT INTO warehouse (organization_id, site_id, code, name, warehouse_type, country_code, address) VALUES
    (@org, @site_jkt, 'WH-JKT-MAIN', 'Jakarta Main Warehouse',  'STORAGE',      'ID', 'Jl. Raya Industri No. 1'),
    (@org, @site_jkt, 'WH-JKT-PROD', 'Jakarta Production Store','PRODUCTION',  'ID', 'Jl. Raya Industri No. 2'),
    (@org, (SELECT id FROM site WHERE code = 'SBY1'), 'WH-SBY-DC', 'Surabaya Distribution Center', 'DISTRIBUTION', 'ID', 'Jl.ifolds No. 9');

SET @wh_main := (SELECT id FROM warehouse WHERE code = 'WH-JKT-MAIN');
SET @wh_prod := (SELECT id FROM warehouse WHERE code = 'WH-JKT-PROD');

INSERT INTO rack (warehouse_id, code, name) VALUES
    (@wh_main, 'R-A', 'Rack A'), (@wh_main, 'R-B', 'Rack B'),
    (@wh_prod, 'R-P1', 'Production Rack 1');

INSERT INTO storage_location (warehouse_id, rack_id, code, name, location_type, capacity)
SELECT @wh_main, r.id, 'BIN-A-01', 'Bin A-01', 'BIN', 500.000 FROM rack r WHERE r.warehouse_id = @wh_main AND r.code = 'R-A'
UNION ALL
SELECT @wh_main, r.id, 'BIN-A-02', 'Bin A-02', 'BIN', 500.000 FROM rack r WHERE r.warehouse_id = @wh_main AND r.code = 'R-A'
UNION ALL
SELECT @wh_main, r.id, 'BIN-B-01', 'Bin B-01', 'BIN', 750.000 FROM rack r WHERE r.warehouse_id = @wh_main AND r.code = 'R-B'
UNION ALL
SELECT @wh_prod, r.id, 'STAGE-01', 'Staging Area 1', 'STAGING', NULL FROM rack r WHERE r.warehouse_id = @wh_prod AND r.code = 'R-P1';

INSERT INTO dock (warehouse_id, code, name, dock_type) VALUES
    (@wh_main, 'DOCK-IN-1',  'Receiving Dock 1',  'RECEIVING'),
    (@wh_main, 'DOCK-OUT-1', 'Dispatch Dock 1',   'DISPATCH'),
    (@wh_main, 'DOCK-SUP-1', 'Supply Dock 1',     'SUPPLY'),
    (@wh_prod, 'DOCK-FG-1',  'Finished Goods Dock 1', 'DELIVERY');

INSERT INTO work_center (organization_id, site_id, code, name, work_center_type, capacity_hours_per_day) VALUES
    (@org, @site_jkt, 'WC-CNC-1', 'CNC Machine 1', 'MACHINE', 16.00),
    (@org, @site_jkt, 'WC-ASM-1', 'Assembly Station 1', 'WORKSTATION', 8.00);

INSERT INTO production_line (organization_id, site_id, code, name, warehouse_id, work_center_id, capacity_per_day) VALUES
    (@org, @site_jkt, 'PL-ASSY-1', 'Assembly Line 1', @wh_prod,
        (SELECT id FROM work_center WHERE code = 'WC-ASM-1'), 250.0000),
    (@org, @site_jkt, 'PL-CNC-1', 'CNC Line 1', @wh_prod,
        (SELECT id FROM work_center WHERE code = 'WC-CNC-1'), 120.0000);

INSERT INTO work_calendar (organization_id, site_id, code, name, timezone, description) VALUES
    (@org, @site_jkt, 'CAL-STD', 'Standard 5-day week', 'Asia/Jakarta', 'Mon-Fri 08:00-16:00');

-- Document numbering: site-scoped for transactions, org-wide for master
INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix, padding, start_at, period_type) VALUES
    (@org, NULL,      'SO',  'Sales Order',        'SO-',  '', 6, 1, 'YEARLY'),
    (@org, NULL,      'PO',  'Purchase Order',     'PO-',  '', 6, 1, 'YEARLY'),
    (@org, NULL,      'INV', 'Invoice',            'INV-', '', 8, 1, 'YEARLY'),
    (@org, NULL,      'GRN', 'Goods Receipt',      'GRN-', '', 6, 1, 'YEARLY'),
    (@org, NULL,      'ADJ', 'Stock Adjustment',   'ADJ-', '', 6, 1, 'YEARLY'),
    (@org, @site_jkt, 'MO',  'Manufacturing Order','MO-',  '', 6, 1, 'YEARLY');
