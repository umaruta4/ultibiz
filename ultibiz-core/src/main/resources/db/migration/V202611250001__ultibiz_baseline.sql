-- UltiBiz baseline schema (P0 Foundation)
-- MariaDB 11.8+ / InnoDB / utf8mb4

CREATE TABLE organization (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code           VARCHAR(32)  NOT NULL,
    name           VARCHAR(255) NOT NULL,
    base_currency  CHAR(3)      NOT NULL DEFAULT 'IDR',
    default_locale VARCHAR(10)  NOT NULL DEFAULT 'en',
    timezone       VARCHAR(64)  NOT NULL DEFAULT 'Asia/Jakarta',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version    BIGINT       NOT NULL DEFAULT 0,
    deleted_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_organization_code (code)
) ENGINE = InnoDB;

CREATE TABLE country (
    code           CHAR(2)      NOT NULL,
    name           VARCHAR(128) NOT NULL,
    currency       CHAR(3)      NOT NULL,
    default_locale VARCHAR(10)  NOT NULL DEFAULT 'en',
    timezone       VARCHAR(64)  NOT NULL,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE site (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    country_code    CHAR(2)         NOT NULL,
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    site_type       VARCHAR(32)     NOT NULL DEFAULT 'FACTORY',
    timezone        VARCHAR(64)     NOT NULL DEFAULT 'Asia/Jakarta',
    address         TEXT            NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    deleted_at      DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_site_org_code (organization_id, code),
    KEY idx_site_org (organization_id),
    CONSTRAINT fk_site_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_site_country FOREIGN KEY (country_code) REFERENCES country (code)
) ENGINE = InnoDB;

CREATE TABLE app_user (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    username        VARCHAR(128)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    display_name    VARCHAR(255)    NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    default_locale  VARCHAR(10)     NOT NULL DEFAULT 'en',
    theme           VARCHAR(8)      NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version     BIGINT          NOT NULL DEFAULT 0,
    deleted_at      DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_app_user_username (username),
    UNIQUE KEY uq_app_user_email (email),
    KEY idx_app_user_org (organization_id),
    CONSTRAINT fk_app_user_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE role (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NULL,           -- NULL = global role
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_role_org_code (organization_id, code),
    CONSTRAINT fk_role_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

CREATE TABLE permission (
    code        VARCHAR(128) NOT NULL,
    description VARCHAR(255) NOT NULL,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE role_permission (
    role_id         BIGINT UNSIGNED NOT NULL,
    permission_code VARCHAR(128)    NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_rp_perm FOREIGN KEY (permission_code) REFERENCES permission (code)
) ENGINE = InnoDB;

CREATE TABLE user_role (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES role (id)
) ENGINE = InnoDB;

CREATE TABLE user_site_scope (
    user_id BIGINT UNSIGNED NOT NULL,
    site_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (user_id, site_id),
    CONSTRAINT fk_uss_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_uss_site FOREIGN KEY (site_id) REFERENCES site (id)
) ENGINE = InnoDB;

CREATE TABLE audit_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    actor           VARCHAR(128)    NOT NULL,
    action          VARCHAR(128)    NOT NULL,
    entity_type     VARCHAR(128)    NOT NULL,
    entity_id       VARCHAR(64)     NOT NULL,
    before_json     JSON            NULL,
    after_json      JSON            NULL,
    ip              VARCHAR(45)     NULL,
    user_agent      VARCHAR(255)    NULL,
    correlation_id  CHAR(36)        NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_org_time (organization_id, created_at),
    KEY idx_audit_entity (entity_type, entity_id)
) ENGINE = InnoDB;

-- Spring Session (JDBC) — schema matches spring-session-jdbc, managed by Flyway
-- (spring.session.jdbc.initialize-schema=never).
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID           CHAR(36)     NOT NULL,
    SESSION_ID           CHAR(36)     NOT NULL,
    CREATION_TIME        BIGINT       NOT NULL,
    LAST_ACCESS_TIME     BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT         NOT NULL,
    EXPIRY_TIME          BIGINT       NOT NULL,
    PRINCIPAL_NAME       VARCHAR(100) NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Seed data (demo tenant). The admin password hash is a placeholder that
-- matches nothing; AdminBootstrapService replaces it on first boot from
-- ULTIBIZ_ADMIN_PASSWORD or a generated value printed to the log once.
-- ---------------------------------------------------------------------------

INSERT INTO country (code, name, currency, default_locale, timezone) VALUES
    ('ID', 'Indonesia',    'IDR', 'id', 'Asia/Jakarta'),
    ('US', 'United States','USD', 'en', 'America/New_York');

INSERT INTO organization (code, name, base_currency, default_locale, timezone)
VALUES ('ACME', 'Acme Industrial', 'IDR', 'id', 'Asia/Jakarta');

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

INSERT INTO site (organization_id, country_code, code, name, site_type) VALUES
    (@org, 'ID', 'JKT1', 'Jakarta Plant 1',  'FACTORY'),
    (@org, 'ID', 'SBY1', 'Surabaya DC',      'DISTRIBUTION');

INSERT INTO app_user (organization_id, username, password_hash, display_name, email, default_locale)
VALUES (@org, 'admin', '{locked}', 'Administrator', 'admin@ultibiz.local', 'id');

INSERT INTO role (organization_id, code, name) VALUES
    (NULL, 'ADMIN', 'Administrator'),
    (NULL, 'USER',  'User');

INSERT INTO permission (code, description) VALUES
    ('admin.access',   'Administration access'),
    ('dashboard.view', 'View dashboard');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p WHERE r.code = 'ADMIN';

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u JOIN role r
WHERE u.username = 'admin' AND r.code = 'ADMIN';

INSERT INTO user_site_scope (user_id, site_id)
SELECT u.id, s.id FROM app_user u JOIN site s WHERE u.username = 'admin';
