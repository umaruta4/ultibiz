-- UltiBiz P11 — the e-mail settings.
--
-- One configuration per organisation: the transport (none, SMTP or an API
-- provider), the sender address and the credentials. The secrets are never
-- written in clear text: the password, the API key and the webhook secret
-- are sealed with the deployment key (the same cipher as the AI keys).

SET @org := (SELECT id FROM organization WHERE code = 'ACME');

CREATE TABLE email_provider_config (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id         BIGINT UNSIGNED NOT NULL,
    provider                VARCHAR(24)     NOT NULL DEFAULT 'NONE',
                            -- NONE|SMTP|MANDRILL|SENDGRID|MAILGUN|WEBHOOK
    from_address            VARCHAR(160)    NOT NULL DEFAULT 'ultibiz@localhost',
    smtp_host               VARCHAR(160)    NULL,
    smtp_port               INT             NOT NULL DEFAULT 587,
    smtp_user               VARCHAR(160)    NULL,
    smtp_password_encrypted TEXT            NULL,
    api_key_encrypted       TEXT            NULL,   -- mandrill / sendgrid / mailgun
    mailgun_domain          VARCHAR(160)    NULL,
    mailgun_region          VARCHAR(8)      NOT NULL DEFAULT 'us',
    webhook_url             VARCHAR(255)    NULL,
    webhook_secret_encrypted TEXT           NULL,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    updated_by              VARCHAR(64)     NULL,
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_email_config_org (organization_id),
    CONSTRAINT fk_email_config_org FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB;

INSERT IGNORE INTO email_provider_config (organization_id, provider, from_address)
VALUES (@org, 'NONE', 'ultibiz@localhost');
