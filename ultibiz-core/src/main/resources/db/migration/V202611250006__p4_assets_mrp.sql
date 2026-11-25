-- UltiBiz P4 — asset management and manufacturing extensions.
--
-- Assets: register, categories with the depreciation rule, monthly depreciation
-- runs (straight line, declining balance, units of production), disposal,
-- transfer between sites, maintenance plans and meter readings.
--
-- Manufacturing extensions: multi-level BOM (phantom items), engineering
-- change notices, shift calendar, lot/serial traceability and the data the
-- MRP suggestion engine needs (lead time, safety stock, on order).

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

-- ---------------------------------------------------------------------------
-- Assets
-- ---------------------------------------------------------------------------

CREATE TABLE asset_category (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id               BIGINT UNSIGNED NOT NULL,
    code                          VARCHAR(32)     NOT NULL,
    name                          VARCHAR(255)    NOT NULL,
    depreciation_method           VARCHAR(24)     NOT NULL DEFAULT 'STRAIGHT_LINE',
    useful_life_months            INT             NOT NULL DEFAULT 60,
    salvage_percent               DECIMAL(9,4)    NOT NULL DEFAULT 0.0000,
    total_production_units        DECIMAL(18,3)   NULL,   -- for UNITS_OF_PRODUCTION
    asset_account_id              BIGINT UNSIGNED NULL,
    accumulated_depreciation_account_id BIGINT UNSIGNED NULL,
    depreciation_expense_account_id BIGINT UNSIGNED NULL,
    is_active                     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_asset_category_org_code (organization_id, code),
    CONSTRAINT fk_asset_category_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_asset_category_asset_acc FOREIGN KEY (asset_account_id) REFERENCES account (id),
    CONSTRAINT fk_asset_category_accdep FOREIGN KEY (accumulated_depreciation_account_id) REFERENCES account (id),
    CONSTRAINT fk_asset_category_depexp FOREIGN KEY (depreciation_expense_account_id) REFERENCES account (id)
) ENGINE = InnoDB;

CREATE TABLE asset (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id         BIGINT UNSIGNED NOT NULL,
    site_id                 BIGINT UNSIGNED NOT NULL,
    category_id             BIGINT UNSIGNED NOT NULL,
    code                    VARCHAR(64)     NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    acquisition_date        DATE            NOT NULL,
    acquisition_cost        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    salvage_value           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    accumulated_depreciation DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    book_value              DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    units_produced          DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    location_id             BIGINT UNSIGNED NULL,
    serial_number           VARCHAR(64)     NULL,
    supplier_party_id       BIGINT UNSIGNED NULL,
    purchase_order_id       BIGINT UNSIGNED NULL,
    warranty_end            DATE            NULL,
    status                  VARCHAR(24)     NOT NULL DEFAULT 'IN_SERVICE',  -- IN_SERVICE|IDLE|DISPOSED
    last_maintenance_date   DATE            NULL,
    next_maintenance_date   DATE            NULL,
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_asset_org_code (organization_id, code),
    KEY idx_asset_site (organization_id, site_id, status),
    KEY idx_asset_category (category_id),
    CONSTRAINT fk_asset_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_asset_site FOREIGN KEY (site_id) REFERENCES site (id),
    CONSTRAINT fk_asset_category FOREIGN KEY (category_id) REFERENCES asset_category (id),
    CONSTRAINT fk_asset_location FOREIGN KEY (location_id) REFERENCES storage_location (id),
    CONSTRAINT fk_asset_party FOREIGN KEY (supplier_party_id) REFERENCES party (id),
    CONSTRAINT fk_asset_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id)
) ENGINE = InnoDB;

CREATE TABLE asset_depreciation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    asset_id        BIGINT UNSIGNED NOT NULL,
    period_year     INT             NOT NULL,
    period_month    TINYINT         NOT NULL,
    method          VARCHAR(24)     NOT NULL,
    depreciation_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    accumulated_amount  DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    book_value_after    DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    journal_id      BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_asset_depreciation (asset_id, period_year, period_month),
    KEY idx_asset_depreciation_period (organization_id, period_year, period_month),
    CONSTRAINT fk_asset_depreciation_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_asset_depreciation_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE asset_disposal (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    asset_id        BIGINT UNSIGNED NOT NULL,
    disposal_date   DATE            NOT NULL,
    proceeds        DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    book_value      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    gain_loss       DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    reason          VARCHAR(255)    NULL,
    journal_id      BIGINT UNSIGNED NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_asset_disposal_asset (asset_id),
    CONSTRAINT fk_asset_disposal_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_asset_disposal_journal FOREIGN KEY (journal_id) REFERENCES journal_entry (id)
) ENGINE = InnoDB;

CREATE TABLE asset_transfer (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    asset_id        BIGINT UNSIGNED NOT NULL,
    from_site_id    BIGINT UNSIGNED NOT NULL,
    to_site_id      BIGINT UNSIGNED NOT NULL,
    transfer_date   DATE            NOT NULL,
    note            VARCHAR(255)    NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_asset_transfer_asset (asset_id),
    CONSTRAINT fk_asset_transfer_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_asset_transfer_from FOREIGN KEY (from_site_id) REFERENCES site (id),
    CONSTRAINT fk_asset_transfer_to FOREIGN KEY (to_site_id) REFERENCES site (id)
) ENGINE = InnoDB;

CREATE TABLE maintenance_plan (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    category_id     BIGINT UNSIGNED NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    interval_months INT             NOT NULL DEFAULT 12,
    description     VARCHAR(512)    NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_maintenance_plan_org_code (organization_id, code),
    CONSTRAINT fk_maintenance_plan_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_maintenance_plan_category FOREIGN KEY (category_id) REFERENCES asset_category (id)
) ENGINE = InnoDB;

CREATE TABLE asset_maintenance (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    asset_id        BIGINT UNSIGNED NOT NULL,
    plan_id         BIGINT UNSIGNED NULL,
    due_date        DATE            NOT NULL,
    done_date       DATE            NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',  -- OPEN|DONE|OVERDUE
    cost            DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    note            VARCHAR(512)    NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_asset_maintenance_asset (asset_id, due_date),
    KEY idx_asset_maintenance_status (organization_id, status),
    CONSTRAINT fk_asset_maintenance_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_asset_maintenance_plan FOREIGN KEY (plan_id) REFERENCES maintenance_plan (id)
) ENGINE = InnoDB;

CREATE TABLE asset_meter_reading (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    asset_id        BIGINT UNSIGNED NOT NULL,
    reading_date    DATE            NOT NULL,
    meter_value     DECIMAL(18,3)   NOT NULL,
    previous_value  DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    consumption     DECIMAL(18,3)   NOT NULL DEFAULT 0.000,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_asset_meter_asset (asset_id, reading_date),
    CONSTRAINT fk_asset_meter_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Manufacturing extensions
-- ---------------------------------------------------------------------------

-- BOM lines can point at a parent line: the explosion walks the tree.
ALTER TABLE bom_line
    ADD COLUMN parent_line_id BIGINT UNSIGNED NULL,
    ADD COLUMN is_phantom BOOLEAN NOT NULL DEFAULT FALSE,
    ADD KEY idx_bom_line_parent (parent_line_id),
    ADD CONSTRAINT fk_bom_line_parent FOREIGN KEY (parent_line_id) REFERENCES bom_line (id);

-- Engineering change notice: a pending change of a BOM line
CREATE TABLE ecn (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    description     VARCHAR(512)    NULL,
    bom_id          BIGINT UNSIGNED NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',  -- DRAFT|APPROVED|APPLIED|CANCELLED
    effective_date  DATE            NULL,
    created_by      VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ecn_org_code (organization_id, code),
    CONSTRAINT fk_ecn_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_ecn_bom FOREIGN KEY (bom_id) REFERENCES bom (id)
) ENGINE = InnoDB;

CREATE TABLE ecn_line (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ecn_id          BIGINT UNSIGNED NOT NULL,
    bom_line_id     BIGINT UNSIGNED NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    old_quantity    DECIMAL(18,3)   NULL,
    new_quantity    DECIMAL(18,3)   NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ecn_line_ecn (ecn_id),
    CONSTRAINT fk_ecn_line_ecn FOREIGN KEY (ecn_id) REFERENCES ecn (id),
    CONSTRAINT fk_ecn_line_bom_line FOREIGN KEY (bom_line_id) REFERENCES bom_line (id),
    CONSTRAINT fk_ecn_line_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;

-- Shift / capacity calendar
CREATE TABLE shift_definition (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    hours           DECIMAL(5,2)    NOT NULL DEFAULT 8.00,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shift_org_code (organization_id, code),
    CONSTRAINT fk_shift_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- Product planning data for the MRP suggestion engine
ALTER TABLE product
    ADD COLUMN lead_time_days INT NOT NULL DEFAULT 0,
    ADD COLUMN safety_stock DECIMAL(18,3) NOT NULL DEFAULT 0.000,
    ADD COLUMN is_phantom BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN minimum_order_quantity DECIMAL(18,3) NOT NULL DEFAULT 0.000;

-- Lot / serial traceability
CREATE TABLE lot (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    lot_code        VARCHAR(64)     NOT NULL,
    serial_number   VARCHAR(64)     NULL,
    produced_at     DATE            NULL,
    expires_at      DATE            NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lot_org_product_code (organization_id, product_id, lot_code),
    CONSTRAINT fk_lot_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_lot_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;

ALTER TABLE stock_movement
    ADD COLUMN lot_id BIGINT UNSIGNED NULL,
    ADD KEY idx_stock_movement_lot (lot_id),
    ADD CONSTRAINT fk_stock_movement_lot FOREIGN KEY (lot_id) REFERENCES lot (id);

ALTER TABLE goods_receipt_line
    ADD COLUMN lot_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_goods_receipt_line_lot FOREIGN KEY (lot_id) REFERENCES lot (id);

-- ---------------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------------

INSERT INTO asset_category (organization_id, code, name, depreciation_method, useful_life_months,
    salvage_percent, total_production_units, asset_account_id, accumulated_depreciation_account_id,
    depreciation_expense_account_id) VALUES
    (@org, 'MACHINE', 'Machinery', 'STRAIGHT_LINE', 60, 0.1000, NULL,
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '5300' LIMIT 1)),
    (@org, 'VEHICLE', 'Vehicles', 'DECLINING_BALANCE', 48, 0.2500, NULL,
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '5300' LIMIT 1)),
    (@org, 'TOOL', 'Tools', 'UNITS_OF_PRODUCTION', 36, 0.0500, 100000.000,
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '1400' LIMIT 1),
        (SELECT id FROM account WHERE code = '5300' LIMIT 1));

SET @cat_machine := (SELECT id FROM asset_category WHERE code = 'MACHINE');
SET @cat_vehicle := (SELECT id FROM asset_category WHERE code = 'VEHICLE');
SET @site_jkt := (SELECT id FROM site WHERE code = 'JKT1');

INSERT INTO asset (organization_id, site_id, category_id, code, name, acquisition_date,
    acquisition_cost, salvage_value, book_value, status, warranty_end) VALUES
    (@org, @site_jkt, @cat_machine, 'AST-0001', 'CNC Machine 1', '2024-01-15', 120000000.00,
        12000000.00, 120000000.00, 'IN_SERVICE', '2027-01-15'),
    (@org, @site_jkt, @cat_vehicle, 'AST-0002', 'Delivery Truck 1', '2023-06-01', 90000000.00,
        22500000.00, 90000000.00, 'IN_SERVICE', '2026-06-01');

INSERT INTO maintenance_plan (organization_id, category_id, code, name, interval_months, description) VALUES
    (@org, @cat_machine, 'MP-MACHINE', 'Quarterly service', 3, 'Lubrication, calibration, safety check'),
    (@org, @cat_vehicle, 'MP-VEHICLE', 'Half-yearly service', 6, 'Oil, tyres, brake check');

INSERT INTO shift_definition (organization_id, code, name, start_time, end_time, hours) VALUES
    (@org, 'DAY',   'Day shift',   '08:00:00', '16:00:00', 8.00),
    (@org, 'NIGHT', 'Night shift', '20:00:00', '04:00:00', 8.00);

-- planning data for the MRP suggestions
UPDATE product SET lead_time_days = 30, safety_stock = 20.000, minimum_order_quantity = 10.000
WHERE code IN ('BRG-0001', 'BRG-0002');
