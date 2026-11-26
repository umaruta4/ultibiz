-- UltiBiz P7 — AI assistant.
--
-- One configuration per organisation (provider, base URL, model, the encrypted
-- key, temperature, timeout and the "may we talk to an external provider"
-- switch), the conversations with their messages, the tool calls (read tools
-- run at once, write tools wait for a human confirmation), the insights the
-- anomaly rules produce and the knowledge vault with its vector chunks.

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

CREATE TABLE ai_provider_config (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    provider        VARCHAR(24)     NOT NULL DEFAULT 'ECHO',  -- ECHO|OPENAI_COMPATIBLE|ANTHROPIC|OLLAMA
    base_url        VARCHAR(255)    NULL,
    model           VARCHAR(64)     NULL,
    api_key_encrypted TEXT          NULL,       -- never plain text
    temperature     DECIMAL(3,2)    NOT NULL DEFAULT 0.20,
    timeout_ms      INT             NOT NULL DEFAULT 30000,
    max_tokens      INT             NOT NULL DEFAULT 1024,
    allow_external  BOOLEAN         NOT NULL DEFAULT FALSE,   -- off by default
    system_prompt   TEXT            NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_config_org (organization_id),
    CONSTRAINT fk_ai_config_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE ai_conversation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    user_id         VARCHAR(64)     NOT NULL,
    title           VARCHAR(255)    NULL,
    context_route   VARCHAR(128)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_conversation_user (organization_id, user_id, id),
    CONSTRAINT fk_ai_conversation_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE ai_message (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id   BIGINT UNSIGNED NOT NULL,
    role              VARCHAR(16)     NOT NULL,   -- user|assistant|system|tool
    content           MEDIUMTEXT      NOT NULL,
    provider          VARCHAR(24)     NULL,
    model             VARCHAR(64)     NULL,
    prompt_tokens     INT             NOT NULL DEFAULT 0,
    completion_tokens INT             NOT NULL DEFAULT 0,
    cost              DECIMAL(14,8)   NOT NULL DEFAULT 0.00000000,
    latency_ms        INT             NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_message_conversation (conversation_id, id),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id)
) ENGINE = InnoDB;

CREATE TABLE ai_tool_call (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT UNSIGNED NOT NULL,
    message_id      BIGINT UNSIGNED NULL,
    tool_name       VARCHAR(64)     NOT NULL,
    kind            VARCHAR(8)      NOT NULL,   -- READ|WRITE
    arguments       TEXT            NOT NULL,
    status          VARCHAR(16)     NOT NULL,   -- EXECUTED|PROPOSED|CONFIRMED|REJECTED|FAILED
    diff            TEXT            NULL,
    result          TEXT            NULL,
    confirmed_by    VARCHAR(128)    NULL,
    confirmed_at    DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_tool_call_conversation (conversation_id, id),
    CONSTRAINT fk_ai_tool_call_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id)
) ENGINE = InnoDB;

-- The findings of the anomaly rules
CREATE TABLE ai_alert (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id   BIGINT UNSIGNED NOT NULL,
    rule_code         VARCHAR(64)     NOT NULL,
    severity          VARCHAR(16)     NOT NULL,   -- INFO|WARNING|CRITICAL
    title             VARCHAR(255)    NOT NULL,
    evidence          TEXT            NOT NULL,
    confidence        DECIMAL(4,3)    NOT NULL DEFAULT 0.500,
    suggested_action  VARCHAR(512)    NULL,
    entity            VARCHAR(64)     NULL,
    entity_id         VARCHAR(64)     NULL,
    status            VARCHAR(16)     NOT NULL DEFAULT 'OPEN',  -- OPEN|ACKNOWLEDGED|DISMISSED
    acknowledged_by   VARCHAR(128)    NULL,
    acknowledged_at   DATETIME(6)     NULL,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_alert_rule_entity (organization_id, rule_code, entity, entity_id),
    KEY idx_ai_alert_status (organization_id, status, created_at),
    CONSTRAINT fk_ai_alert_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- The knowledge vault: the documents and their vector chunks
CREATE TABLE knowledge_document (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    source          VARCHAR(255)    NULL,
    language        VARCHAR(8)      NOT NULL DEFAULT 'id',
    content         MEDIUMTEXT      NOT NULL,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_knowledge_document_code (organization_id, code),
    CONSTRAINT fk_knowledge_document_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE knowledge_chunk (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    document_id     BIGINT UNSIGNED NOT NULL,
    chunk_no        INT             NOT NULL,
    content         MEDIUMTEXT      NOT NULL,
    -- the vector itself, in the native MariaDB type. The column is nullable
    -- on purpose: a VECTOR index needs NOT NULL columns, and the MariaDB
    -- builds that ship the type but not the vector functions (11.8 on
    -- Ubuntu 24.04, for example) cannot fill the column at all. The retrieval
    -- therefore works on the text copy below; the native column and its HNSW
    -- index can be switched on with one statement as soon as the server
    -- offers the vector functions.
    embedding       VECTOR(768)     NULL,
    -- the same vector as text: the retrieval works on every server
    embedding_text  MEDIUMTEXT      NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_knowledge_chunk (document_id, chunk_no),
    FULLTEXT KEY ft_knowledge_chunk (content),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id)
) ENGINE = InnoDB;

-- The native HNSW index is added with the vector functions:
--   CREATE VECTOR INDEX vix_knowledge_chunk_embedding
--       ON knowledge_chunk (embedding) M = 8 DISTANCE = EUCLIDEAN;

-- ---------------------------------------------------------------------------
-- Seed: the assistant configuration and two knowledge documents
-- ---------------------------------------------------------------------------

INSERT INTO ai_provider_config (organization_id, provider, base_url, model, api_key_encrypted,
    temperature, timeout_ms, max_tokens, allow_external, system_prompt) VALUES
    (@org, 'ECHO', NULL, 'ultibiz-echo', NULL, 0.20, 30000, 1024, FALSE,
        CONCAT('You are the UltiBiz assistant. Answer from the ERP data and the knowledge base. ',
               'When you propose a write action, explain the change first.'));

INSERT INTO knowledge_document (organization_id, code, title, source, language, content) VALUES
    (@org, 'KB-0001', 'Stok minimum dan pemesanan ulang',
        'https://kb.ultibiz.example/stok-minimum', 'id',
        CONCAT('Stok minimum disimpan per produk pada kolom safety_stock. Jika permintaan MRP ',
               'lebih besar dari stok tersedia ditambah pesanan yang sedang berjalan, sistem ',
               'menyarankan pembelian. Saran pembelian dihitung setiap kali halaman MRP dibuka.')),
    (@org, 'KB-0002', 'Penyusutan aset',
        'https://kb.ultibiz.example/penyusutan', 'id',
        CONCAT('Aset disusutkan bulanan dengan salah satu dari tiga metode: garis lurus, saldo ',
               'menurun atau unit produksi. Nilai buku tidak pernah turun di bawah nilai sisa. ',
               'Jurnal penyusutan dibuat satu kali untuk seluruh aset pada periode tersebut.'));
