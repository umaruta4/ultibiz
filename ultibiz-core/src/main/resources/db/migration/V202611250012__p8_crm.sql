-- ============================================================================
-- P8 plugin 3: CRM — leads, pipeline, activities, contacts, campaigns,
-- opportunities with the quote hand-off to Sales, and territories.
-- ============================================================================

CREATE TABLE crm_territory (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_crm_territory (organization_id, code)
) ENGINE = InnoDB;

-- The pipeline stages, ordered; the probability feeds the weighted pipeline.
CREATE TABLE crm_stage (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    position        INT             NOT NULL,
    probability     DECIMAL(5,2)    NOT NULL DEFAULT 0.00,       -- 0..100
    is_won          BOOLEAN         NOT NULL DEFAULT FALSE,
    is_lost         BOOLEAN         NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_crm_stage (organization_id, code),
    UNIQUE KEY uq_crm_stage_position (organization_id, position)
) ENGINE = InnoDB;

CREATE TABLE crm_campaign (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    channel         VARCHAR(64)     NOT NULL DEFAULT 'EMAIL',    -- EMAIL | EVENT | ADS | REFERRAL
    start_date      DATE            NULL,
    end_date        DATE            NULL,
    budget          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | DONE
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_crm_campaign (organization_id, code)
) ENGINE = InnoDB;

CREATE TABLE crm_contact (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    first_name      VARCHAR(128)    NOT NULL,
    last_name       VARCHAR(128)    NOT NULL,
    email           VARCHAR(255)    NULL,
    phone           VARCHAR(64)     NULL,
    company         VARCHAR(255)    NULL,
    party_id        BIGINT UNSIGNED NULL,          -- the master-data party, once converted
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_crm_contact_org (organization_id, last_name)
) ENGINE = InnoDB;

CREATE TABLE crm_lead (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    company         VARCHAR(255)    NOT NULL,
    contact_name    VARCHAR(255)    NULL,
    email           VARCHAR(255)    NULL,
    phone           VARCHAR(64)     NULL,
    source          VARCHAR(64)     NOT NULL DEFAULT 'MANUAL',   -- MANUAL | WEB | REFERRAL | CAMPAIGN
    territory_id    BIGINT UNSIGNED NULL,
    campaign_id     BIGINT UNSIGNED NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'NEW',      -- NEW | QUALIFIED | CONVERTED | LOST
    party_id        BIGINT UNSIGNED NULL,          -- the party created at the conversion
    lost_reason     VARCHAR(255)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_crm_lead (organization_id, code),
    CONSTRAINT fk_crm_lead_territory FOREIGN KEY (territory_id) REFERENCES crm_territory (id),
    CONSTRAINT fk_crm_lead_campaign FOREIGN KEY (campaign_id) REFERENCES crm_campaign (id)
) ENGINE = InnoDB;

CREATE TABLE crm_opportunity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    lead_id         BIGINT UNSIGNED NULL,
    party_id        BIGINT UNSIGNED NULL,          -- the customer, when known
    stage_id        BIGINT UNSIGNED NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL DEFAULT 0.00,
    currency        CHAR(3)         NOT NULL DEFAULT 'IDR',
    expected_close  DATE            NULL,
    owner           VARCHAR(128)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',     -- OPEN | WON | LOST
    lost_reason     VARCHAR(255)    NULL,
    sales_order_id  BIGINT UNSIGNED NULL,          -- the quote hand-off to Sales
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_crm_opportunity (organization_id, code),
    KEY idx_crm_opportunity_stage (stage_id, status),
    CONSTRAINT fk_crm_opportunity_lead FOREIGN KEY (lead_id) REFERENCES crm_lead (id),
    CONSTRAINT fk_crm_opportunity_stage FOREIGN KEY (stage_id) REFERENCES crm_stage (id)
) ENGINE = InnoDB;

CREATE TABLE crm_activity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    opportunity_id  BIGINT UNSIGNED NULL,
    lead_id         BIGINT UNSIGNED NULL,
    contact_id      BIGINT UNSIGNED NULL,
    activity_type   VARCHAR(16)     NOT NULL,      -- CALL | MEETING | EMAIL | TASK
    subject         VARCHAR(255)    NOT NULL,
    due_date        DATE            NULL,
    done            BOOLEAN         NOT NULL DEFAULT FALSE,
    notes           VARCHAR(1024)   NULL,
    owner           VARCHAR(128)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_crm_activity_opp (opportunity_id, done),
    CONSTRAINT fk_crm_activity_opp FOREIGN KEY (opportunity_id) REFERENCES crm_opportunity (id),
    CONSTRAINT fk_crm_activity_lead FOREIGN KEY (lead_id) REFERENCES crm_lead (id),
    CONSTRAINT fk_crm_activity_contact FOREIGN KEY (contact_id) REFERENCES crm_contact (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: the default pipeline, two territories and one campaign.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);

INSERT IGNORE INTO crm_territory (organization_id, code, name) VALUES
    (@org, 'TER-JKT', 'Jakarta'),
    (@org, 'TER-NAS', 'National');

INSERT IGNORE INTO crm_stage (organization_id, code, name, position, probability, is_won, is_lost) VALUES
    (@org, 'NEW', 'New', 1, 10.00, FALSE, FALSE),
    (@org, 'QUALIFIED', 'Qualified', 2, 25.00, FALSE, FALSE),
    (@org, 'PROPOSAL', 'Proposal', 3, 50.00, FALSE, FALSE),
    (@org, 'NEGOTIATION', 'Negotiation', 4, 75.00, FALSE, FALSE),
    (@org, 'WON', 'Won', 5, 100.00, TRUE, FALSE),
    (@org, 'LOST', 'Lost', 6, 0.00, FALSE, TRUE);

INSERT IGNORE INTO crm_campaign (organization_id, code, name, channel, start_date, budget)
    VALUES (@org, 'CMP-2026Q4', 'Year-end rollout', 'EMAIL', '2026-11-01', 5000000.00);

INSERT IGNORE INTO crm_lead (organization_id, code, company, contact_name, email, source,
                             territory_id, status)
    SELECT @org, 'LEAD-0001', 'PT Samudera Distribusi', 'Ibu Ratna',
           'ratna@samudera.example', 'CAMPAIGN', t.id, 'NEW'
    FROM crm_territory t WHERE t.code = 'TER-JKT' LIMIT 1;
