-- UltiBiz P5 — data exchange: canonical documents, partners, inbox/outbox.
--
-- The exchange layer receives documents from partners (REST/JSON, Peppol UBL,
-- X12/EDIFACT EDI, CSV), validates them, maps them into the canonical model
-- and applies them as DRAFT business documents — and the other way around for
-- outbound traffic. Every message keeps its raw payload, its idempotency key
-- and a validation report; a failed message is retried with backoff and lands
-- in the dead-letter queue until an operator replays it.

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

-- ---------------------------------------------------------------------------
-- Partner registry: one row per trading partner per protocol
-- ---------------------------------------------------------------------------

CREATE TABLE exchange_partner (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    protocol        VARCHAR(16)     NOT NULL,   -- REST_JSON|PEPPOL|X12|EDIFACT|CSV
    direction       VARCHAR(8)      NOT NULL DEFAULT 'BOTH',  -- INBOUND|OUTBOUND|BOTH
    endpoint_url    VARCHAR(512)    NULL,
    auth_type       VARCHAR(16)     NOT NULL DEFAULT 'NONE',  -- NONE|TOKEN|OAUTH2|MTLS
    auth_reference  VARCHAR(255)    NULL,       -- name of the credential, never the secret
    peppol_participant_id VARCHAR(64) NULL,     -- e.g. 0192:987654321
    edi_interchange_id    VARCHAR(64) NULL,     -- X12 ISA06 / EDIFACT UNB sender id
    edi_qualifier         VARCHAR(8)  NULL,     -- X12 ISA05 qualifier (ZZ, 01, ...)
    mapping_config  TEXT            NULL,       -- JSON: partner-specific field mapping
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_exchange_partner_org_code (organization_id, code),
    CONSTRAINT fk_exchange_partner_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Inbox / outbox: one row per exchanged document
-- ---------------------------------------------------------------------------

CREATE TABLE exchange_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    partner_id      BIGINT UNSIGNED NOT NULL,
    direction       VARCHAR(8)      NOT NULL,   -- INBOUND|OUTBOUND
    protocol        VARCHAR(16)     NOT NULL,
    document_type   VARCHAR(32)     NOT NULL,   -- INVOICE|CREDIT_NOTE|ORDER|DESPATCH|PURCHASE_INVOICE|STATEMENT
    document_number VARCHAR(64)     NULL,
    idempotency_key VARCHAR(128)    NOT NULL,
    payload         MEDIUMTEXT      NOT NULL,   -- the raw payload, exactly as received
    payload_format  VARCHAR(8)      NOT NULL,   -- JSON|XML|EDI|CSV
    canonical       MEDIUMTEXT      NULL,       -- the mapped canonical document (JSON)
    status          VARCHAR(16)     NOT NULL DEFAULT 'RECEIVED',
    -- RECEIVED -> PARSED -> VALIDATED -> MAPPED -> APPLIED          (inbound)
    -- QUEUED -> SENT                                                (outbound)
    -- REJECTED (fatal validation), FAILED (retryable), DEAD (DLQ)
    error           VARCHAR(1024)   NULL,
    validation_report MEDIUMTEXT    NULL,       -- the issues found (rule id + severity + message)
    attempts        INT             NOT NULL DEFAULT 0,
    max_attempts    INT             NOT NULL DEFAULT 5,
    next_retry_at   DATETIME(6)     NULL,
    applied_document_type VARCHAR(32) NULL,     -- e.g. purchase_invoice
    applied_document_id   BIGINT UNSIGNED NULL,
    correlation_id  VARCHAR(64)     NULL,       -- outbound: the source document reference
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- the idempotency key makes a re-delivery a no-op
    UNIQUE KEY uq_exchange_idempotency (organization_id, partner_id, direction, idempotency_key),
    KEY idx_exchange_status_retry (status, next_retry_at),
    KEY idx_exchange_partner (partner_id),
    CONSTRAINT fk_exchange_message_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_exchange_message_partner FOREIGN KEY (partner_id) REFERENCES exchange_partner (id)
) ENGINE = InnoDB;

-- The acknowledgement we sent (997 / CONTRL) for an inbound EDI interchange
CREATE TABLE exchange_ack (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_id      BIGINT UNSIGNED NOT NULL,
    ack_type        VARCHAR(8)      NOT NULL,   -- 997|CONTRL
    accepted        BOOLEAN         NOT NULL,
    payload         MEDIUMTEXT      NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_exchange_ack_message (message_id),
    CONSTRAINT fk_exchange_ack_message FOREIGN KEY (message_id) REFERENCES exchange_message (id)
) ENGINE = InnoDB;

-- The scheduler lock: one row per job so two replicas never deliver twice
CREATE TABLE exchange_job_lock (
    name            VARCHAR(64)     NOT NULL,
    locked_by       VARCHAR(128)    NOT NULL,
    locked_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (name)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: the loopback partners used by the demo and by the CI simulator
-- ---------------------------------------------------------------------------

INSERT INTO exchange_partner (organization_id, code, name, protocol, direction, endpoint_url,
    auth_type, peppol_participant_id, edi_interchange_id, edi_qualifier, mapping_config) VALUES
    (@org, 'SIM-REST', 'REST/JSON loopback', 'REST_JSON', 'BOTH', '/api/exchange/sim/rest',
        'NONE', NULL, NULL, NULL, NULL),
    (@org, 'SIM-PEPPOL', 'Peppol simulator', 'PEPPOL', 'BOTH', '/api/exchange/sim/peppol',
        'NONE', '0192:987654321', NULL, NULL, NULL),
    (@org, 'SIM-X12', 'X12 loopback', 'X12', 'BOTH', '/api/exchange/sim/x12',
        'NONE', NULL, 'ULTIBIZ', 'ZZ', NULL),
    (@org, 'SIM-EDIFACT', 'EDIFACT loopback', 'EDIFACT', 'BOTH', '/api/exchange/sim/edifact',
        'NONE', NULL, 'ULTIBIZ', NULL, NULL),
    (@org, 'SIM-CSV', 'CSV flat file', 'CSV', 'BOTH', NULL, 'NONE', NULL, NULL, NULL, NULL);
