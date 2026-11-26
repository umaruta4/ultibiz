-- ============================================================================
-- P8 plugin 5: Knowledge Vault — a document store with folders, tags,
-- versioning, retention policies and the RAG corpus (chunk + embed + index).
-- ============================================================================

CREATE TABLE kv_folder (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    parent_id       BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_kv_folder (organization_id, code),
    CONSTRAINT fk_kv_folder_parent FOREIGN KEY (parent_id) REFERENCES kv_folder (id)
) ENGINE = InnoDB;

CREATE TABLE kv_tag (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    name            VARCHAR(64)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kv_tag (organization_id, name)
) ENGINE = InnoDB;

-- The versions of a document: the content of every revision is kept.
CREATE TABLE kv_version (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    document_id     BIGINT UNSIGNED NOT NULL,
    version_no      INT             NOT NULL,
    title           VARCHAR(255)    NOT NULL,
    content         MEDIUMTEXT      NOT NULL,
    note            VARCHAR(255)    NULL,
    author          VARCHAR(128)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_kv_version (document_id, version_no),
    FULLTEXT KEY ft_kv_version (title, content),
    CONSTRAINT fk_kv_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id)
) ENGINE = InnoDB;

CREATE TABLE kv_document_tag (
    document_id     BIGINT UNSIGNED NOT NULL,
    tag_id          BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (document_id, tag_id),
    CONSTRAINT fk_kv_doc_tag_doc FOREIGN KEY (document_id) REFERENCES knowledge_document (id),
    CONSTRAINT fk_kv_doc_tag_tag FOREIGN KEY (tag_id) REFERENCES kv_tag (id)
) ENGINE = InnoDB;

-- The retention policies: how long a document is kept and what happens then.
CREATE TABLE kv_retention_policy (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    folder_id       BIGINT UNSIGNED NULL,          -- NULL = every folder
    retain_months   INT             NOT NULL,
    action          VARCHAR(16)     NOT NULL DEFAULT 'ARCHIVE',    -- ARCHIVE | DELETE
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kv_retention (organization_id, code),
    CONSTRAINT fk_kv_retention_folder FOREIGN KEY (folder_id) REFERENCES kv_folder (id)
) ENGINE = InnoDB;

-- The knowledge document gets a folder, a retention policy, a version counter
-- and the legal hold that stops the retention run.
ALTER TABLE knowledge_document
    ADD COLUMN folder_id BIGINT UNSIGNED NULL AFTER source,
    ADD COLUMN retention_policy_id BIGINT UNSIGNED NULL AFTER folder_id,
    ADD COLUMN current_version INT NOT NULL DEFAULT 1 AFTER content,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE AFTER current_version,
    ADD COLUMN archived_at DATETIME(6) NULL AFTER legal_hold,
    ADD CONSTRAINT fk_knowledge_document_folder FOREIGN KEY (folder_id) REFERENCES kv_folder (id),
    ADD CONSTRAINT fk_knowledge_document_retention
        FOREIGN KEY (retention_policy_id) REFERENCES kv_retention_policy (id);

-- ---------------------------------------------------------------------------
-- Seed: one folder, one tag and one retention policy.
-- ---------------------------------------------------------------------------
SET @org := (SELECT id FROM organization ORDER BY id LIMIT 1);

INSERT IGNORE INTO kv_folder (organization_id, code, name) VALUES
    (@org, 'ROOT', 'General'),
    (@org, 'OPS', 'Operations');

INSERT IGNORE INTO kv_tag (organization_id, name) VALUES
    (@org, 'handbook'),
    (@org, 'procedure');

INSERT IGNORE INTO kv_retention_policy (organization_id, code, name, folder_id, retain_months,
                                        action)
    SELECT @org, 'RET-STD', 'Standard retention', f.id, 60, 'ARCHIVE'
    FROM kv_folder f WHERE f.code = 'ROOT' LIMIT 1;

-- The seeded knowledge documents get the standard policy.
UPDATE knowledge_document d
    JOIN kv_retention_policy p ON p.code = 'RET-STD'
SET d.retention_policy_id = p.id
WHERE d.organization_id = p.organization_id AND d.retention_policy_id IS NULL;
