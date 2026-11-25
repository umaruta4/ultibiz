-- UltiBiz P3 — sales orders, purchase orders, goods receipts, stock, BOM and
-- work orders.
--
-- Money is DECIMAL(18,2); quantities DECIMAL(18,3); unit costs DECIMAL(18,4).
-- Stock is kept in stock_balance (moving average valuation) and every change
-- is written to stock_movement as an immutable ledger of movements.
--
-- Table order follows the foreign keys: a goods receipt points at a work
-- order, so the work order (and its BOM) must exist first.

CREATE TABLE sales_order (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    order_number    VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    order_date      DATE            NOT NULL,
    delivery_date   DATE            NULL,
    warehouse_id    BIGINT UNSIGNED NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    exchange_rate   DECIMAL(18,8)   NOT NULL DEFAULT 1.00000000,
    status          VARCHAR(24)     NOT NULL DEFAULT 'DRAFT',  -- DRAFT|CONFIRMED|PARTIALLY_SHIPPED|SHIPPED|INVOICED|CANCELLED
    subtotal        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    notes           VARCHAR(512)    NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sales_order_number (organization_id, order_number),
    KEY idx_sales_order_party (organization_id, party_id),
    KEY idx_sales_order_status (organization_id, status),
    CONSTRAINT fk_sales_order_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_sales_order_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_sales_order_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_sales_order_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
) ENGINE = InnoDB;


CREATE TABLE sales_order_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id       BIGINT UNSIGNED NOT NULL,
    line_no        INT             NOT NULL,
    product_id     BIGINT UNSIGNED NULL,
    description    VARCHAR(255)    NOT NULL,
    quantity       DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    unit_price     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    discount       DECIMAL(9,4)    NOT NULL DEFAULT 0.0000,   -- fraction, 0.05 = 5%
    tax_category_id BIGINT UNSIGNED NULL,
    tax_rate       DECIMAL(9,6)    NULL,
    net_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total_amount   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    quantity_shipped DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    quantity_invoiced DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sales_order_line (order_id, line_no),
    KEY idx_sales_order_line_product (product_id),
    CONSTRAINT fk_sales_order_line_order FOREIGN KEY (order_id) REFERENCES sales_order (id),
    CONSTRAINT fk_sales_order_line_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE purchase_order (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    order_number    VARCHAR(64)     NOT NULL,
    party_id        BIGINT UNSIGNED NOT NULL,
    order_date      DATE            NOT NULL,
    expected_date   DATE            NULL,
    warehouse_id    BIGINT UNSIGNED NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    exchange_rate   DECIMAL(18,8)   NOT NULL DEFAULT 1.00000000,
    status          VARCHAR(24)     NOT NULL DEFAULT 'DRAFT',  -- DRAFT|CONFIRMED|PARTIALLY_RECEIVED|RECEIVED|CANCELLED
    subtotal        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    total           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    notes           VARCHAR(512)    NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_order_number (organization_id, order_number),
    KEY idx_purchase_order_party (organization_id, party_id),
    KEY idx_purchase_order_status (organization_id, status),
    CONSTRAINT fk_purchase_order_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_purchase_order_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_purchase_order_party FOREIGN KEY (party_id) REFERENCES party (id),
    CONSTRAINT fk_purchase_order_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
) ENGINE = InnoDB;


CREATE TABLE purchase_order_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id       BIGINT UNSIGNED NOT NULL,
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
    quantity_received DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_order_line (order_id, line_no),
    CONSTRAINT fk_purchase_order_line_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_purchase_order_line_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE bom (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    quantity        DECIMAL(18,3)   NOT NULL DEFAULT 1.000,   -- output quantity of the BOM
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bom_org_code (organization_id, code),
    KEY idx_bom_product (product_id),
    CONSTRAINT fk_bom_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_bom_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_bom_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE bom_line (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bom_id        BIGINT UNSIGNED NOT NULL,
    line_no       INT             NOT NULL,
    component_product_id BIGINT UNSIGNED NOT NULL,
    quantity      DECIMAL(18,3)   NOT NULL DEFAULT 1.000,
    scrap_percent DECIMAL(9,4)    NOT NULL DEFAULT 0.0000,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_bom_line (bom_id, line_no),
    CONSTRAINT fk_bom_line_bom FOREIGN KEY (bom_id) REFERENCES bom (id),
    CONSTRAINT fk_bom_line_product FOREIGN KEY (component_product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE work_order (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    order_number    VARCHAR(64)     NOT NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    bom_id          BIGINT UNSIGNED NULL,
    production_line_id BIGINT UNSIGNED NULL,
    warehouse_id    BIGINT UNSIGNED NOT NULL,
    quantity        DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    quantity_produced DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    status          VARCHAR(24)     NOT NULL DEFAULT 'PLANNED', -- PLANNED|RELEASED|IN_PROGRESS|COMPLETED|CANCELLED
    planned_start   DATE            NULL,
    planned_end     DATE            NULL,
    actual_start    DATETIME(6)     NULL,
    actual_end      DATETIME(6)     NULL,
    material_cost   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_work_order_number (organization_id, order_number),
    KEY idx_work_order_product (product_id),
    CONSTRAINT fk_work_order_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_work_order_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_work_order_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_work_order_bom FOREIGN KEY (bom_id) REFERENCES bom (id),
    CONSTRAINT fk_work_order_line FOREIGN KEY (production_line_id) REFERENCES production_line (id),
    CONSTRAINT fk_work_order_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
) ENGINE = InnoDB;


CREATE TABLE work_order_material (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    work_order_id  BIGINT UNSIGNED NOT NULL,
    line_no        INT             NOT NULL,
    product_id     BIGINT UNSIGNED NOT NULL,
    quantity_required DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    quantity_issued  DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_work_order_material (work_order_id, line_no),
    CONSTRAINT fk_work_order_material_wo FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_work_order_material_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE goods_receipt (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    receipt_number  VARCHAR(64)     NOT NULL,
    source_type     VARCHAR(32)     NOT NULL DEFAULT 'PURCHASE',  -- PURCHASE|SALES|ADJUSTMENT|PRODUCTION
    purchase_order_id BIGINT UNSIGNED NULL,
    sales_order_id  BIGINT UNSIGNED NULL,
    work_order_id   BIGINT UNSIGNED NULL,
    party_id        BIGINT UNSIGNED NULL,
    warehouse_id    BIGINT UNSIGNED NOT NULL,
    receipt_date    DATE            NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    status          VARCHAR(16)     NOT NULL DEFAULT 'POSTED',
    notes           VARCHAR(512)    NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_goods_receipt_number (organization_id, receipt_number),
    KEY idx_goods_receipt_po (purchase_order_id),
    KEY idx_goods_receipt_so (sales_order_id),
    CONSTRAINT fk_goods_receipt_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_goods_receipt_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_goods_receipt_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_goods_receipt_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_goods_receipt_so FOREIGN KEY (sales_order_id) REFERENCES sales_order (id),
    CONSTRAINT fk_goods_receipt_wo FOREIGN KEY (work_order_id) REFERENCES work_order (id)
) ENGINE = InnoDB;


CREATE TABLE goods_receipt_line (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    receipt_id    BIGINT UNSIGNED NOT NULL,
    line_no       INT             NOT NULL,
    product_id    BIGINT UNSIGNED NOT NULL,
    location_id   BIGINT UNSIGNED NULL,
    quantity      DECIMAL(18,3)   NOT NULL DEFAULT 0.000,   -- signed: negative for issues
    unit_cost     DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
    total_cost    DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_goods_receipt_line (receipt_id, line_no),
    CONSTRAINT fk_goods_receipt_line_receipt FOREIGN KEY (receipt_id) REFERENCES goods_receipt (id),
    CONSTRAINT fk_goods_receipt_line_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_goods_receipt_line_location FOREIGN KEY (location_id) REFERENCES storage_location (id)
) ENGINE = InnoDB;


CREATE TABLE stock_movement (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    warehouse_id    BIGINT UNSIGNED NOT NULL,
    location_id     BIGINT UNSIGNED NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    movement_date   DATE            NOT NULL,
    quantity        DECIMAL(18,3)   NOT NULL,        -- signed: + in, - out
    unit_cost       DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
    total_value     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    balance_after   DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    source_type     VARCHAR(32)     NOT NULL,
    source_id       BIGINT UNSIGNED NULL,
    source_line     INT             NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_stock_movement_product (organization_id, product_id, warehouse_id, movement_date),
    KEY idx_stock_movement_source (source_type, source_id),
    CONSTRAINT fk_stock_movement_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_stock_movement_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_stock_movement_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_stock_movement_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


CREATE TABLE stock_balance (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    warehouse_id    BIGINT UNSIGNED NOT NULL,
    location_id     BIGINT UNSIGNED NULL,
    location_key    BIGINT UNSIGNED AS (IFNULL(location_id, 0)) STORED,
    product_id      BIGINT UNSIGNED NOT NULL,
    quantity        DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    average_cost    DECIMAL(18,4)   NOT NULL DEFAULT 0.0000,
    total_value     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_balance (organization_id, warehouse_id, location_key, product_id),
    KEY idx_stock_balance_product (organization_id, product_id),
    CONSTRAINT fk_stock_balance_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_stock_balance_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_stock_balance_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_stock_balance_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;


-- Flyway runs every migration on its own connection, so the demo organization
-- has to be resolved inside this script (a session variable from an earlier
-- migration would be NULL here).
SET @org := (SELECT id FROM organization WHERE code = 'ACME');

-- ---------------------------------------------------------------------------
-- Document numbering
-- ---------------------------------------------------------------------------

-- SO / PO / GRN already exist (master data migration), only the new ones here
INSERT IGNORE INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
    padding, start_at, counter, period_type) VALUES
    (@org, NULL, 'SO',  'Sales Order',      'SO-',  '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'PO',  'Purchase Order',   'PO-',  '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'GRN', 'Goods Receipt',    'GRN-', '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'DO',  'Delivery Order',   'DO-',  '', 6, 1, 0, 'YEARLY'),
    (@org, NULL, 'WO',  'Work Order',       'WO-',  '', 6, 1, 0, 'YEARLY');

-- A bill of material for the demo product PALLET-EUR (made of two bearings)
INSERT INTO bom (organization_id, site_id, product_id, code, name, quantity)
SELECT @org, NULL, p.id, 'BOM-PALLET', 'Pallet assembly', 1.000
FROM product p WHERE p.code = 'PALLET-EUR';

INSERT INTO bom_line (bom_id, line_no, component_product_id, quantity)
SELECT b.id, 1, p.id, 2.000 FROM bom b JOIN product p ON p.code = 'BRG-0001'
WHERE b.code = 'BOM-PALLET'
UNION ALL
SELECT b.id, 2, p.id, 1.000 FROM bom b JOIN product p ON p.code = 'BRG-0002'
WHERE b.code = 'BOM-PALLET';
