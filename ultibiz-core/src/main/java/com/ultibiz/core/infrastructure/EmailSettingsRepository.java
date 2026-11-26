package com.ultibiz.core.infrastructure;

import com.ultibiz.core.mail.EmailSettings;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The e-mail configuration of the organisations. */
@Repository
public class EmailSettingsRepository {

    private final JdbcTemplate jdbc;

    public EmailSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The configuration of an organisation (null when there is none). */
    public EmailSettings find(long organizationId) {
        List<EmailSettings> rows = jdbc.query(
                "SELECT id, organization_id, provider, from_address, smtp_host, smtp_port,"
                        + " smtp_user, smtp_password_encrypted, api_key_encrypted,"
                        + " mailgun_domain, mailgun_region, webhook_url, webhook_secret_encrypted,"
                        + " is_active FROM email_provider_config WHERE organization_id = ?",
                (rs, row) -> new EmailSettings(
                        rs.getLong("organization_id"), rs.getString("provider"),
                        rs.getString("from_address"), rs.getString("smtp_host"),
                        rs.getInt("smtp_port"), rs.getString("smtp_user"),
                        rs.getString("smtp_password_encrypted"), rs.getString("api_key_encrypted"),
                        rs.getString("mailgun_domain"), rs.getString("mailgun_region"),
                        rs.getString("webhook_url"), rs.getString("webhook_secret_encrypted"),
                        rs.getBoolean("is_active")),
                organizationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Saves the configuration (the secrets are sealed before they arrive here). */
    public void save(EmailSettings settings, String actor) {
        int updated = jdbc.update(
                "UPDATE email_provider_config SET provider = ?, from_address = ?, smtp_host = ?,"
                        + " smtp_port = ?, smtp_user = ?, smtp_password_encrypted = ?,"
                        + " api_key_encrypted = ?, mailgun_domain = ?, mailgun_region = ?,"
                        + " webhook_url = ?, webhook_secret_encrypted = ?, is_active = ?,"
                        + " updated_by = ? WHERE organization_id = ?",
                settings.providerName(), settings.fromAddress(), settings.smtpHost(),
                settings.smtpPort(), settings.smtpUser(), settings.smtpPassword(),
                settings.apiKey(), settings.mailgunDomain(),
                settings.mailgunRegion() == null ? "us" : settings.mailgunRegion(),
                settings.webhookUrl(), settings.webhookSecret(), settings.active(),
                actor, settings.organizationId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO email_provider_config (organization_id, provider, from_address,"
                            + " smtp_host, smtp_port, smtp_user, smtp_password_encrypted,"
                            + " api_key_encrypted, mailgun_domain, mailgun_region, webhook_url,"
                            + " webhook_secret_encrypted, is_active, updated_by)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    settings.organizationId(), settings.providerName(), settings.fromAddress(),
                    settings.smtpHost(), settings.smtpPort(), settings.smtpUser(),
                    settings.smtpPassword(), settings.apiKey(), settings.mailgunDomain(),
                    settings.mailgunRegion() == null ? "us" : settings.mailgunRegion(),
                    settings.webhookUrl(), settings.webhookSecret(), settings.active(), actor);
        }
    }

    /** Whether a secret is stored (for the interface: "the key is set"). */
    public boolean hasSecret(long organizationId, String column) {
        List<String> allowed = List.of("smtp_password_encrypted", "api_key_encrypted",
                "webhook_secret_encrypted");
        if (!allowed.contains(column)) {
            throw new IllegalArgumentException("Unknown secret");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_provider_config WHERE organization_id = ?"
                        + " AND " + column + " IS NOT NULL AND " + column + " <> ''",
                Integer.class, organizationId);
        return count != null && count > 0;
    }
}
