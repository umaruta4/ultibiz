-- ============================================================================
-- P8 plugin 1: HRM — employees, org structure, contracts, attendance & leave,
-- timesheets, payroll export, training/certifications, H&S incidents,
-- expense claims and the GDPR hooks.
-- ============================================================================

-- The org structure: positions with a parent, grouped by department.
CREATE TABLE hr_position (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    department      VARCHAR(128)    NOT NULL DEFAULT 'General',
    parent_id       BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_position (organization_id, code),
    CONSTRAINT fk_hr_position_parent FOREIGN KEY (parent_id) REFERENCES hr_position (id)
) ENGINE = InnoDB;

-- The cost centres the payroll and the expenses are allocated to.
CREATE TABLE hr_cost_centre (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_cost_centre (organization_id, code)
) ENGINE = InnoDB;

CREATE TABLE hr_employee (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    employee_no     VARCHAR(64)     NOT NULL,
    user_id         BIGINT UNSIGNED NULL,          -- the login, when the employee has one
    position_id     BIGINT UNSIGNED NULL,
    cost_centre_id  BIGINT UNSIGNED NULL,
    first_name      VARCHAR(128)    NOT NULL,
    last_name       VARCHAR(128)    NOT NULL,
    personal_email  VARCHAR(255)    NULL,          -- personal data (GDPR scope)
    phone           VARCHAR(64)     NULL,          -- personal data (GDPR scope)
    national_id     VARCHAR(64)     NULL,          -- personal data (NPWP/KTP, GDPR scope)
    birth_date      DATE            NULL,          -- personal data (GDPR scope)
    hire_date       DATE            NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | INACTIVE
    deleted_at      DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_employee (organization_id, employee_no),
    KEY idx_hr_employee_org (organization_id, status),
    CONSTRAINT fk_hr_employee_position FOREIGN KEY (position_id) REFERENCES hr_position (id),
    CONSTRAINT fk_hr_employee_cost_centre FOREIGN KEY (cost_centre_id) REFERENCES hr_cost_centre (id)
) ENGINE = InnoDB;

CREATE TABLE hr_contract (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    contract_no     VARCHAR(64)     NOT NULL,
    contract_type   VARCHAR(16)     NOT NULL,      -- PERMANENT | FIXED_TERM | INTERN | FREELANCE
    start_date      DATE            NOT NULL,
    end_date        DATE            NULL,          -- NULL = open-ended
    base_salary     DECIMAL(18,2)   NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    weekly_hours    DECIMAL(5,2)    NOT NULL DEFAULT 40.00,
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',    -- DRAFT | ACTIVE | ENDED
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_contract (organization_id, contract_no),
    KEY idx_hr_contract_employee (employee_id, status),
    CONSTRAINT fk_hr_contract_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

CREATE TABLE hr_leave_type (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    annual_allowance DECIMAL(5,1)   NOT NULL DEFAULT 12.0,
    paid            BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_leave_type (organization_id, code)
) ENGINE = InnoDB;

CREATE TABLE hr_leave_request (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    leave_type_id   BIGINT UNSIGNED NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    days            DECIMAL(5,1)    NOT NULL,      -- the working days, computed on submit
    reason          VARCHAR(512)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED | APPROVED | REJECTED | CANCELLED
    decided_by      VARCHAR(128)    NULL,
    decided_at      DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_hr_leave_employee (employee_id, status),
    CONSTRAINT fk_hr_leave_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id),
    CONSTRAINT fk_hr_leave_type FOREIGN KEY (leave_type_id) REFERENCES hr_leave_type (id)
) ENGINE = InnoDB;

-- The timesheets feed the project costs in P8/Projects.
CREATE TABLE hr_timesheet (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    work_date       DATE            NOT NULL,
    project_code    VARCHAR(64)     NULL,          -- NULL = internal work
    hours           DECIMAL(5,2)    NOT NULL,
    billable        BOOLEAN         NOT NULL DEFAULT FALSE,
    description     VARCHAR(512)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED | APPROVED | REJECTED
    approved_by     VARCHAR(128)    NULL,
    project_key     VARCHAR(64)     AS (IFNULL(project_code, '')) STORED,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_timesheet (organization_id, employee_id, work_date, project_key),
    CONSTRAINT fk_hr_timesheet_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

-- The payroll export: one run per period, one line per employee.
CREATE TABLE hr_payroll_run (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    period          CHAR(7)         NOT NULL,      -- YYYY-MM
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',     -- OPEN | EXPORTED
    exported_by     VARCHAR(128)    NULL,
    exported_at     DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_payroll_run (organization_id, period)
) ENGINE = InnoDB;

CREATE TABLE hr_payroll_line (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id          BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    base_amount     DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    allowances      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    deductions      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    net_amount      DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    cost_centre     VARCHAR(64)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_payroll_line (run_id, employee_id),
    CONSTRAINT fk_hr_payroll_line_run FOREIGN KEY (run_id) REFERENCES hr_payroll_run (id),
    CONSTRAINT fk_hr_payroll_line_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

CREATE TABLE hr_training (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    provider        VARCHAR(255)    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_training (organization_id, code)
) ENGINE = InnoDB;

CREATE TABLE hr_certification (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    training_id     BIGINT UNSIGNED NOT NULL,
    certified_at    DATE            NOT NULL,
    expires_at      DATE            NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_hr_cert_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id),
    CONSTRAINT fk_hr_cert_training FOREIGN KEY (training_id) REFERENCES hr_training (id)
) ENGINE = InnoDB;

-- The H&S incident register feeds the helpdesk in P8/Helpdesk.
CREATE TABLE hr_incident (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    site_id         BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NULL,          -- the person involved, when known
    incident_date   DATE            NOT NULL,
    category        VARCHAR(64)     NOT NULL,      -- INJURY | NEAR_MISS | EQUIPMENT | ENVIRONMENT
    severity        VARCHAR(16)     NOT NULL,      -- LOW | MEDIUM | HIGH | CRITICAL
    description     VARCHAR(1024)   NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',     -- OPEN | INVESTIGATING | CLOSED
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_hr_incident_org (organization_id, status)
) ENGINE = InnoDB;

CREATE TABLE hr_expense_claim (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    claim_no        VARCHAR(64)     NOT NULL,
    total           DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    status          VARCHAR(16)     NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED | APPROVED | REJECTED | PAID
    decided_by      VARCHAR(128)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hr_expense_claim (organization_id, claim_no),
    CONSTRAINT fk_hr_expense_claim_employee FOREIGN KEY (employee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

CREATE TABLE hr_expense_line (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    claim_id        BIGINT UNSIGNED NOT NULL,
    expense_date    DATE            NOT NULL,
    category        VARCHAR(64)     NOT NULL,      -- TRAVEL | MEAL | SUPPLIES | OTHER
    amount          DECIMAL(18,2)   NOT NULL,
    receipt_ref     VARCHAR(128)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_hr_expense_line_claim FOREIGN KEY (claim_id) REFERENCES hr_expense_claim (id)
) ENGINE = InnoDB;

-- The GDPR erasure log: who asked, when, and whether the anonymisation ran.
CREATE TABLE hr_erasure (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    employee_id     BIGINT UNSIGNED NOT NULL,
    requested_by    VARCHAR(128)    NOT NULL,
    requested_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at    DATETIME(6)     NULL,
    PRIMARY KEY (id),
    KEY idx_hr_erasure_employee (employee_id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: a cost centre, two positions, the leave types and one employee who
-- is linked to the admin login. Resolved inside this script — every Flyway
-- migration runs on its own connection.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);
SET @site := (SELECT id FROM site ORDER BY id LIMIT 1);

INSERT IGNORE INTO hr_cost_centre (organization_id, code, name) VALUES
    (@org, 'CC-OPS', 'Operations'),
    (@org, 'CC-ADM', 'Administration');

INSERT IGNORE INTO hr_position (organization_id, code, title, department, parent_id) VALUES
    (@org, 'POS-CEO', 'Director', 'Management', NULL),
    (@org, 'POS-ACC', 'Accountant', 'Finance', NULL);

INSERT IGNORE INTO hr_leave_type (organization_id, code, name, annual_allowance, paid) VALUES
    (@org, 'ANNUAL', 'Annual leave', 12.0, TRUE),
    (@org, 'SICK', 'Sick leave', 12.0, TRUE),
    (@org, 'UNPAID', 'Unpaid leave', 30.0, FALSE);

INSERT IGNORE INTO hr_employee (organization_id, site_id, employee_no, user_id, position_id,
                                cost_centre_id, first_name, last_name, hire_date, status)
    SELECT @org, @site, 'EMP-0001', u.id, p.id, c.id, 'Admin', 'UltiBiz', '2026-01-01', 'ACTIVE'
    FROM app_user u, hr_position p, hr_cost_centre c
    WHERE u.username = 'admin' AND p.code = 'POS-CEO' AND c.code = 'CC-ADM' LIMIT 1;

INSERT IGNORE INTO hr_contract (organization_id, employee_id, contract_no, contract_type,
                                start_date, base_salary, currency, weekly_hours, status)
    SELECT @org, e.id, 'CON-0001', 'PERMANENT', '2026-01-01', 10000000.00, 'IDR', 40.00, 'ACTIVE'
    FROM hr_employee e WHERE e.employee_no = 'EMP-0001' LIMIT 1;
