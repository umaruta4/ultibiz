-- ============================================================================
-- P8 plugin 2: Projects — projects with WBS, budgets, milestones, resource
-- assignments and the cost/revenue tracking that feeds the accounting.
-- ============================================================================

CREATE TABLE prj_project (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    customer_id     BIGINT UNSIGNED NULL,          -- the customer (party), when billable
    manager         VARCHAR(128)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | ON_HOLD | CLOSED
    start_date      DATE            NOT NULL,
    end_date        DATE            NULL,
    budget_amount   DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    billing_rate    DECIMAL(18,2)   NOT NULL DEFAULT 0.00,       -- per billable hour
    deleted_at      DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prj_project (organization_id, code),
    KEY idx_prj_project_org (organization_id, status)
) ENGINE = InnoDB;

-- The work breakdown structure: a tree per project.
CREATE TABLE prj_wbs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    project_id      BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    parent_id       BIGINT UNSIGNED NULL,
    budget_hours    DECIMAL(8,2)    NOT NULL DEFAULT 0.00,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prj_wbs (project_id, code),
    CONSTRAINT fk_prj_wbs_project FOREIGN KEY (project_id) REFERENCES prj_project (id),
    CONSTRAINT fk_prj_wbs_parent FOREIGN KEY (parent_id) REFERENCES prj_wbs (id)
) ENGINE = InnoDB;

-- The milestones: billing events of the project.
CREATE TABLE prj_milestone (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    project_id      BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    due_date        DATE            NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,       -- 0 = not a billing milestone
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',  -- PENDING | REACHED | INVOICED
    reached_at      DATETIME(6)     NULL,
    journal_id      BIGINT UNSIGNED NULL,          -- the revenue posting
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prj_milestone (project_id, code),
    CONSTRAINT fk_prj_milestone_project FOREIGN KEY (project_id) REFERENCES prj_project (id)
) ENGINE = InnoDB;

-- Who works on the project, and how much of their week.
CREATE TABLE prj_assignment (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    project_id      BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    role            VARCHAR(128)    NOT NULL DEFAULT 'Member',
    hours_per_week  DECIMAL(5,2)   NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_prj_assignment_employee (employee_id, start_date, end_date),
    CONSTRAINT fk_prj_assignment_project FOREIGN KEY (project_id) REFERENCES prj_project (id),
    CONSTRAINT fk_prj_assignment_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: one demo project with a WBS, a billing milestone and the admin as an
-- assignee.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);
SET @site := (SELECT id FROM site ORDER BY id LIMIT 1);
SET @emp := (SELECT id FROM hr_employee WHERE employee_no = 'EMP-0001' LIMIT 1);

INSERT IGNORE INTO prj_project (organization_id, site_id, code, name, customer_id,
                                manager, status, start_date, budget_amount, currency,
                                billing_rate)
    SELECT @org, @site, 'PRJ-DEMO', 'Demo implementation', p.id,
           'admin', 'ACTIVE', '2026-11-01', 50000000.00, 'IDR', 250000.00
    FROM party p WHERE p.code = 'CUST-0001' LIMIT 1;

INSERT IGNORE INTO prj_wbs (organization_id, project_id, code, name, parent_id, budget_hours)
    SELECT @org, pr.id, 'WBS-1', 'Analysis', NULL, 80.00
    FROM prj_project pr WHERE pr.code = 'PRJ-DEMO' LIMIT 1;

INSERT IGNORE INTO prj_wbs (organization_id, project_id, code, name, parent_id, budget_hours)
    SELECT @org, pr.id, 'WBS-2', 'Build', NULL, 160.00
    FROM prj_project pr WHERE pr.code = 'PRJ-DEMO' LIMIT 1;

INSERT IGNORE INTO prj_milestone (organization_id, project_id, code, name, due_date, amount)
    SELECT @org, pr.id, 'MS-1', 'Go-live', '2026-12-15', 20000000.00
    FROM prj_project pr WHERE pr.code = 'PRJ-DEMO' LIMIT 1;

INSERT IGNORE INTO prj_assignment (organization_id, project_id, employee_id, role,
                                   hours_per_week, start_date)
    SELECT @org, pr.id, @emp, 'Lead', 20.00, '2026-11-01'
    FROM prj_project pr WHERE pr.code = 'PRJ-DEMO' LIMIT 1;
