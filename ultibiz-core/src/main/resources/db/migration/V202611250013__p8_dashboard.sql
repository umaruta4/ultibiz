-- ============================================================================
-- P8 plugin 4: Dashboard — role-based KPI tiles, saved views, thresholds and
-- alerts, scheduled report delivery and the insight board.
-- ============================================================================

-- The metric catalogue: every tile points at one metric key and carries the
-- permission codes that make it visible (the roles decide what a user sees).
CREATE TABLE dash_metric (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    title           VARCHAR(128)    NOT NULL,
    module          VARCHAR(32)     NOT NULL,
    metric_key      VARCHAR(64)     NOT NULL,
    format          VARCHAR(16)     NOT NULL DEFAULT 'NUMBER',   -- NUMBER | CURRENCY | PERCENT
    permission_codes VARCHAR(255)   NOT NULL DEFAULT '',         -- empty = everybody
    drill_path      VARCHAR(255)    NULL,                         -- drill-through target
    sort_order      INT             NOT NULL DEFAULT 100,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dash_metric (organization_id, code)
) ENGINE = InnoDB;

-- The saved views: a filter set per user (or shared with the organisation).
CREATE TABLE dash_saved_view (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    title           VARCHAR(128)    NOT NULL,
    module          VARCHAR(32)     NOT NULL,
    filters         MEDIUMTEXT      NOT NULL DEFAULT '{}',
    is_shared       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dash_saved_view (organization_id, user_id, code)
) ENGINE = InnoDB;

-- The thresholds: a metric above (or below) a value raises an alert.
CREATE TABLE dash_threshold (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    metric_key      VARCHAR(64)     NOT NULL,
    label           VARCHAR(128)    NOT NULL,
    comparison      VARCHAR(8)      NOT NULL,      -- GT | GTE | LT | LTE
    value           DECIMAL(18,2)   NOT NULL,
    severity        VARCHAR(16)     NOT NULL DEFAULT 'WARNING',   -- INFO | WARNING | CRITICAL
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dash_threshold (organization_id, metric_key, label)
) ENGINE = InnoDB;

-- The scheduled reports: a subscription per user, the generated files are
-- listed separately.
CREATE TABLE dash_report_subscription (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    report_code     VARCHAR(64)     NOT NULL,
    title           VARCHAR(128)    NOT NULL,
    frequency       VARCHAR(16)     NOT NULL DEFAULT 'MONTHLY',   -- DAILY | WEEKLY | MONTHLY
    format          VARCHAR(8)      NOT NULL DEFAULT 'CSV',       -- CSV | HTML
    email           VARCHAR(255)    NULL,
    next_run_at     DATETIME(6)     NOT NULL,
    last_run_at     DATETIME(6)     NULL,
    last_status     VARCHAR(24)     NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dash_subscription (organization_id, user_id, report_code)
) ENGINE = InnoDB;

CREATE TABLE dash_report_file (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    subscription_id BIGINT UNSIGNED NULL,
    report_code     VARCHAR(64)     NOT NULL,
    period          VARCHAR(32)     NOT NULL,
    format          VARCHAR(8)      NOT NULL,
    file_name       VARCHAR(255)    NOT NULL,
    file_path       VARCHAR(512)    NOT NULL,
    size_bytes      BIGINT          NOT NULL DEFAULT 0,
    delivery_status VARCHAR(24)     NOT NULL DEFAULT 'FILE_READY', -- FILE_READY | DELIVERED | FAILED
    delivery_detail VARCHAR(255)    NULL,
    generated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    generated_by    VARCHAR(128)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dash_report_file_sub (subscription_id, generated_at)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: the default tiles and two thresholds.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);

INSERT IGNORE INTO dash_metric (organization_id, code, title, module, metric_key, format,
                                permission_codes, drill_path, sort_order) VALUES
    (@org, 'REVENUE_MONTH', 'Revenue this month', 'accounting', 'REVENUE_MONTH', 'CURRENCY',
     'accounting.journal.view', '/ar?status=OPEN', 10),
    (@org, 'AR_OVERDUE', 'Overdue receivables', 'accounting', 'AR_OVERDUE_AMOUNT', 'CURRENCY',
     'accounting.ar.view', '/ar?status=OPEN', 20),
    (@org, 'CASH', 'Cash on hand', 'accounting', 'CASH_BALANCE', 'CURRENCY',
     'accounting.journal.view', '/accounting/trial-balance', 30),
    (@org, 'OPEN_SALES_ORDERS', 'Open sales orders', 'sales', 'OPEN_SALES_ORDERS', 'NUMBER',
     'sales.order.view', '/orders/sales', 40),
    (@org, 'OPEN_PURCHASE_ORDERS', 'Open purchase orders', 'purchasing',
     'OPEN_PURCHASE_ORDERS', 'NUMBER', 'purchasing.order.view', '/orders/purchase', 50),
    (@org, 'STOCK_VALUE', 'Stock value', 'inventory', 'STOCK_VALUE', 'CURRENCY',
     'inventory.stock.view', '/inventory', 60),
    (@org, 'HEADCOUNT', 'Active employees', 'hrm', 'HEADCOUNT', 'NUMBER',
     'hrm.employee.view', '/hr', 70),
    (@org, 'OPEN_LEAVES', 'Leave requests waiting', 'hrm', 'OPEN_LEAVES', 'NUMBER',
     'hrm.leave.view', '/hr/leave', 80),
    (@org, 'CRM_PIPELINE', 'Open CRM pipeline', 'crm', 'CRM_PIPELINE', 'CURRENCY',
     'crm.opportunity.view', '/crm/opportunities', 90),
    (@org, 'PROJECT_BUDGET', 'Project budget', 'projects', 'PROJECT_BUDGET', 'CURRENCY',
     'prj.project.view', '/projects', 100);

INSERT IGNORE INTO dash_threshold (organization_id, metric_key, label, comparison, value,
                                  severity) VALUES
    (@org, 'AR_OVERDUE_AMOUNT', 'Overdue receivables are high', 'GT', 50000000.00, 'CRITICAL'),
    (@org, 'OPEN_LEAVES', 'Too many leave requests are waiting', 'GT', 5, 'WARNING');
