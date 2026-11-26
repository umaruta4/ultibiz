-- ============================================================================
-- P8 plugin 6: Helpdesk — ticket intake, SLA tiers, assignment, knowledge
-- suggestions, escalation and the read-only customer portal.
-- ============================================================================

CREATE TABLE hd_queue (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    email           VARCHAR(255)    NULL,
    response_hours  INT             NOT NULL DEFAULT 8,      -- the SLA tier
    resolve_hours   INT             NOT NULL DEFAULT 72,
    escalate_hours  INT             NOT NULL DEFAULT 24,     -- after this the ticket escalates
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hd_queue (organization_id, code)
) ENGINE = InnoDB;

CREATE TABLE hd_ticket (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT UNSIGNED NOT NULL,
    site_id          BIGINT UNSIGNED NOT NULL,
    ticket_no        VARCHAR(32)     NOT NULL,
    subject          VARCHAR(255)    NOT NULL,
    description      MEDIUMTEXT      NOT NULL,
    category         VARCHAR(64)     NOT NULL DEFAULT 'GENERAL',
    priority         VARCHAR(16)     NOT NULL DEFAULT 'NORMAL',  -- LOW | NORMAL | HIGH | URGENT
    status           VARCHAR(16)     NOT NULL DEFAULT 'NEW',      -- NEW | TRIAGED | ASSIGNED |
                                                             -- IN_PROGRESS | WAITING | RESOLVED | CLOSED
    queue_id         BIGINT UNSIGNED NULL,
    assignee_id      BIGINT UNSIGNED NULL,        -- the employee that handles it
    requester_name   VARCHAR(128)    NULL,
    requester_email  VARCHAR(255)    NULL,
    requester_party_id BIGINT UNSIGNED NULL,
    source           VARCHAR(16)     NOT NULL DEFAULT 'PORTAL',  -- PORTAL | EMAIL | PHONE | CHAT
    portal_token     CHAR(32)        NOT NULL,     -- the read-only customer link
    sla_response_due DATETIME(6)     NULL,
    sla_resolve_due  DATETIME(6)     NULL,
    first_response_at DATETIME(6)    NULL,
    resolved_at      DATETIME(6)     NULL,
    closed_at        DATETIME(6)     NULL,
    escalated_at     DATETIME(6)     NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version      BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_hd_ticket_no (organization_id, ticket_no),
    UNIQUE KEY uq_hd_ticket_token (portal_token),
    KEY idx_hd_ticket_status (organization_id, status, sla_resolve_due),
    CONSTRAINT fk_hd_ticket_queue FOREIGN KEY (queue_id) REFERENCES hd_queue (id),
    CONSTRAINT fk_hd_ticket_assignee FOREIGN KEY (assignee_id) REFERENCES hr_employee (id)
) ENGINE = InnoDB;

CREATE TABLE hd_ticket_activity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    ticket_id       BIGINT UNSIGNED NOT NULL,
    activity_type   VARCHAR(16)     NOT NULL,      -- NOTE | REPLY | STATUS | ASSIGN | ESCALATE
    body            MEDIUMTEXT      NULL,
    author          VARCHAR(128)    NOT NULL,
    is_public       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_hd_activity_ticket (ticket_id, created_at),
    CONSTRAINT fk_hd_activity_ticket FOREIGN KEY (ticket_id) REFERENCES hd_ticket (id)
) ENGINE = InnoDB;

-- The knowledge suggestions of a ticket: the vault documents that fit.
CREATE TABLE hd_kb_suggestion (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    ticket_id       BIGINT UNSIGNED NOT NULL,
    document_id     BIGINT UNSIGNED NOT NULL,
    score           DECIMAL(6,3)    NOT NULL DEFAULT 0.000,
    is_accepted     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_hd_suggestion (ticket_id, document_id),
    CONSTRAINT fk_hd_suggestion_ticket FOREIGN KEY (ticket_id) REFERENCES hd_ticket (id),
    CONSTRAINT fk_hd_suggestion_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: two queues with different SLA tiers.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);

INSERT IGNORE INTO hd_queue (organization_id, code, name, email, response_hours, resolve_hours,
                             escalate_hours) VALUES
    (@org, 'Q-SUPPORT', 'Support', 'support@ultibiz.example', 8, 72, 24),
    (@org, 'Q-CRITICAL', 'Critical incidents', 'critical@ultibiz.example', 1, 8, 2);

INSERT IGNORE INTO hd_ticket (organization_id, site_id, ticket_no, subject, description, category,
                             priority, status, queue_id, requester_name, requester_email, source,
                             portal_token, sla_response_due, sla_resolve_due)
    SELECT @org, s.id, 'TKT-0001', 'Printer on floor 2 is offline',
           'The printer on the second floor does not respond since this morning.',
           'EQUIPMENT', 'NORMAL', 'NEW', q.id, 'Dewi Ananda', 'dewi@nusantara.example', 'EMAIL',
           'a1b2c3d4e5f60718293a4b5c6d7e8f90',
           DATE_ADD(NOW(), INTERVAL 8 HOUR), DATE_ADD(NOW(), INTERVAL 72 HOUR)
    FROM site s, hd_queue q WHERE q.code = 'Q-SUPPORT' LIMIT 1;
