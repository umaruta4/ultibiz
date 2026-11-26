-- UltiBiz P6 — offline-first and sync.
--
-- The change log is the delta feed for the clients: every applied change gets
-- a monotonic sequence number per organisation. The idempotency table keeps
-- the answer of every operation a client pushed, so a retried push returns
-- the same result instead of applying twice. The sync entity store holds the
-- documents that may be created and edited while offline (drafts, timesheets,
-- attendance, stock counts, tickets, notes) with an optimistic row_version.

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

CREATE TABLE ub_change_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    sequence_no     BIGINT UNSIGNED NOT NULL,
    entity          VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    operation       VARCHAR(16)     NOT NULL,   -- INSERT|UPDATE|DELETE
    row_version     BIGINT          NOT NULL DEFAULT 0,
    payload         MEDIUMTEXT      NULL,       -- the changed fields as JSON
    actor           VARCHAR(128)    NULL,
    client_id       VARCHAR(64)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_change_log_org_sequence (organization_id, sequence_no),
    KEY idx_change_log_entity (organization_id, entity, entity_id),
    CONSTRAINT fk_change_log_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE ub_idempotency (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    client_id       VARCHAR(64)     NOT NULL,
    op_id           VARCHAR(64)     NOT NULL,
    entity          VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    result          VARCHAR(16)     NOT NULL,   -- APPLIED|CONFLICT|REJECTED|DUPLICATE
    response        MEDIUMTEXT      NULL,       -- the answer that is replayed
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_idempotency_client_op (organization_id, client_id, op_id),
    CONSTRAINT fk_idempotency_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- The documents a client may create and edit while offline
CREATE TABLE ub_sync_entity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    entity          VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    data            MEDIUMTEXT      NOT NULL,   -- the document fields as JSON
    row_version     BIGINT          NOT NULL DEFAULT 1,
    origin_client   VARCHAR(64)     NULL,
    updated_by      VARCHAR(128)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_entity (organization_id, entity, entity_id),
    KEY idx_sync_entity_org (organization_id, entity),
    CONSTRAINT fk_sync_entity_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- The operations that arrived while the target was busy: the worker retries them
CREATE TABLE ub_sync_queue (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    client_id       VARCHAR(64)     NOT NULL,
    op_id           VARCHAR(64)     NOT NULL,
    entity          VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    operation       VARCHAR(16)     NOT NULL,
    base_version    BIGINT          NOT NULL DEFAULT 0,
    payload         MEDIUMTEXT      NOT NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    last_error      VARCHAR(1024)   NULL,
    next_attempt_at DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_queue_op (organization_id, client_id, op_id),
    KEY idx_sync_queue_due (next_attempt_at),
    CONSTRAINT fk_sync_queue_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- The conflicts waiting for an operator decision
CREATE TABLE ub_sync_conflict (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    entity          VARCHAR(64)     NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    client_id       VARCHAR(64)     NOT NULL,
    op_id           VARCHAR(64)     NOT NULL,
    base_version    BIGINT          NOT NULL DEFAULT 0,
    server_version  BIGINT          NOT NULL DEFAULT 0,
    server_data     MEDIUMTEXT      NOT NULL,
    client_data     MEDIUMTEXT      NOT NULL,
    differing_fields TEXT           NOT NULL,     -- JSON: field -> {server, client}
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN',  -- OPEN|KEEP_MINE|KEEP_THEIRS|MERGED
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at     DATETIME(6)     NULL,
    PRIMARY KEY (id),
    KEY idx_sync_conflict_status (organization_id, status),
    CONSTRAINT fk_sync_conflict_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed: the documents that may be created offline
-- ---------------------------------------------------------------------------

INSERT INTO ub_sync_entity (organization_id, entity, entity_id, data, row_version, origin_client,
    updated_by) VALUES
    (@org, 'note', 'NOTE-0001', '{"title":"Shift handover","body":"Line 1 needs a new filter"}',
        1, 'seed', 'system'),
    (@org, 'stock_count', 'SC-2026-11-JKT1', '{"warehouse":"JKT1","counted_quantity":"0"}',
        1, 'seed', 'system'),
    (@org, 'timesheet', 'TS-2026-11-JANEDOE', '{"employee":"JANEDOE","hours":"0","date":"2026-11-01"}',
        1, 'seed', 'system');
