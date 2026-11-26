-- UltiBiz P13 — the tenant registry (one database per tenant).
--
-- The control database holds the identity (the organisations, the users, the
-- roles) and the registry itself; every tenant owns a database with its own
-- business data. The routing (the connection goes to the database of the
-- tenant of the request) is switched on with `ultibiz.tenant.mode=database` —
-- with `single` (the default) everything stays in one database.

CREATE TABLE tenant_registry (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    organization_code VARCHAR(32)   NOT NULL,
    database_name   VARCHAR(64)     NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PROVISIONING',
                            -- PROVISIONING|ACTIVE|SUSPENDED
    provisioned_at  DATETIME(6)     NULL,
    updated_by      VARCHAR(64)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_org (organization_id),
    UNIQUE KEY uq_tenant_database (database_name),
    CONSTRAINT fk_tenant_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

-- The control database is the tenant of the seeded organisation until the
-- mode "database" is switched on.
INSERT IGNORE INTO tenant_registry (organization_id, organization_code, database_name, status)
SELECT id, code, DATABASE(), 'ACTIVE' FROM organization WHERE code = 'ACME';
