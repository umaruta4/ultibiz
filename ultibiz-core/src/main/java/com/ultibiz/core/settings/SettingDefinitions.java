package com.ultibiz.core.settings;

import java.util.ArrayList;
import java.util.List;

/** The registry of the configuration that the administrator may edit. */
public final class SettingDefinitions {

    public static final String GROUP_DATABASE = "The database";
    public static final String GROUP_TENANCY = "The tenancy";
    public static final String GROUP_SSO = "The single sign-on";
    public static final String GROUP_MAIL = "The e-mail (the fallback of the environment)";
    public static final String GROUP_SECURITY = "The security";
    public static final String GROUP_REPORTS = "The reports";

    private static final List<SettingDefinition> ALL = new ArrayList<>();

    static {
        // the database — a change needs a restart of the application
        ALL.add(SettingDefinition.text("db.host", "The database host", GROUP_DATABASE,
                "ULTIBIZ_DB_HOST", "localhost", true, "the host of the MariaDB server"));
        ALL.add(SettingDefinition.number("db.port", "The database port", GROUP_DATABASE,
                "ULTIBIZ_DB_PORT", "3306", true, ""));
        ALL.add(SettingDefinition.text("db.name", "The database name", GROUP_DATABASE,
                "ULTIBIZ_DB_NAME", "ultibiz", true, ""));
        ALL.add(SettingDefinition.text("db.user", "The database user", GROUP_DATABASE,
                "ULTIBIZ_DB_USER", "ultibiz", true, ""));
        ALL.add(SettingDefinition.password("db.password", "The database password", GROUP_DATABASE,
                "ULTIBIZ_DB_PASSWORD", true, "sealed with the deployment key"));
        ALL.add(SettingDefinition.text("db.admin.user", "The administrator of the database",
                GROUP_DATABASE, "ULTIBIZ_DB_ADMIN_USER", "root", true,
                "only used for the provisioning of a tenant database"));
        ALL.add(SettingDefinition.password("db.admin.password", "The password of the database"
                + " administrator", GROUP_DATABASE, "ULTIBIZ_DB_ADMIN_PASSWORD", true, ""));
        ALL.add(SettingDefinition.password("admin.password", "The password of the first"
                + " administrator", GROUP_SECURITY, "ULTIBIZ_ADMIN_PASSWORD", true,
                "used when the first administrator is created"));
        ALL.add(SettingDefinition.password("ai.encryption.key", "The key that seals the secrets",
                GROUP_SECURITY, "ULTIBIZ_AI_ENCRYPTION_KEY", true,
                "changing it makes the stored secrets unreadable — re-enter them afterwards"));

        // the tenancy
        ALL.add(SettingDefinition.select("tenant.mode", "The mode of the tenancy", GROUP_TENANCY,
                "ULTIBIZ_TENANT_MODE", "single", true,
                "single = one database for every organisation; database = one per tenant",
                List.of("single", "database")));

        // the single sign-on
        ALL.add(SettingDefinition.bool("sso.enabled", "The single sign-on is active", GROUP_SSO,
                "ULTIBIZ_SSO_ENABLED", "false", true,
                "the profile sso of Spring Boot does the same; without it the form login is used"));
        ALL.add(SettingDefinition.text("sso.issuer", "The issuer of the identity provider",
                GROUP_SSO, "ULTIBIZ_SSO_ISSUER", "", true, "the URL of the realm of Keycloak"));
        ALL.add(SettingDefinition.text("sso.client.id", "The client id", GROUP_SSO,
                "ULTIBIZ_SSO_CLIENT_ID", "ultibiz", true, ""));
        ALL.add(SettingDefinition.password("sso.client.secret", "The client secret", GROUP_SSO,
                "ULTIBIZ_SSO_CLIENT_SECRET", true, ""));
        ALL.add(SettingDefinition.text("sso.redirect.uri", "The redirect URI", GROUP_SSO,
                "ULTIBIZ_SSO_REDIRECT_URI", "", true,
                "must be registered at the identity provider"));
        ALL.add(SettingDefinition.bool("sso.auto.provision", "The automatic provisioning",
                GROUP_SSO, "ULTIBIZ_SSO_AUTO_PROVISION", "true", false,
                "an unknown user of the provider is created on the first login"));
        ALL.add(SettingDefinition.text("sso.organization", "The organisation of a new user",
                GROUP_SSO, "ULTIBIZ_SSO_ORGANIZATION", "ACME", false, ""));
        ALL.add(SettingDefinition.text("sso.role", "The role of a new user", GROUP_SSO,
                "ULTIBIZ_SSO_ROLE", "USER", false, "the base scheme knows ADMIN and USER"));
        ALL.add(SettingDefinition.text("sso.admin.roles", "The administrator roles of the"
                + " provider", GROUP_SSO, "ULTIBIZ_SSO_ADMIN_ROLES", "ultibiz-admin,admin",
                false, "separated by commas"));

        // the e-mail fallback
        ALL.add(SettingDefinition.select("email.provider", "The transport", GROUP_MAIL,
                "ULTIBIZ_EMAIL_PROVIDER", "", false,
                "the transport of the organisation (the interface) wins",
                List.of("", "mandrill", "sendgrid", "mailgun", "webhook")));
        ALL.add(SettingDefinition.text("email.smtp.host", "The SMTP host", GROUP_MAIL,
                "ULTIBIZ_SMTP_HOST", "", false, ""));
        ALL.add(SettingDefinition.number("email.smtp.port", "The SMTP port", GROUP_MAIL,
                "ULTIBIZ_SMTP_PORT", "587", false, ""));
        ALL.add(SettingDefinition.text("email.smtp.user", "The SMTP user", GROUP_MAIL,
                "ULTIBIZ_SMTP_USER", "", false, ""));
        ALL.add(SettingDefinition.password("email.smtp.password", "The SMTP password", GROUP_MAIL,
                "ULTIBIZ_SMTP_PASSWORD", false, ""));
        ALL.add(SettingDefinition.text("email.from", "The sender address", GROUP_MAIL,
                "ULTIBIZ_SMTP_FROM", "ultibiz@localhost", false, ""));
        ALL.add(SettingDefinition.text("email.mailgun.domain", "The domain of Mailgun", GROUP_MAIL,
                "ULTIBIZ_MAILGUN_DOMAIN", "", false, ""));
        ALL.add(SettingDefinition.select("email.mailgun.region", "The region of Mailgun",
                GROUP_MAIL, "ULTIBIZ_MAILGUN_REGION", "us", false, "", List.of("us", "eu")));
        ALL.add(SettingDefinition.text("email.webhook.url", "The URL of the e-mail webhook",
                GROUP_MAIL, "ULTIBIZ_EMAIL_WEBHOOK_URL", "", false, ""));
        ALL.add(SettingDefinition.password("email.webhook.secret", "The secret of the e-mail"
                + " webhook", GROUP_MAIL, "ULTIBIZ_EMAIL_WEBHOOK_SECRET", false, ""));

        // the security
        ALL.add(SettingDefinition.password("exchange.webhook.secret", "The shared secret of the"
                + " exchange", GROUP_SECURITY, "ULTIBIZ_EXCHANGE_WEBHOOK_SECRET", false,
                "the partners send it in the X-Exchange-Secret header"));
        ALL.add(SettingDefinition.number("rate.limit.requests", "The requests of the rate limit",
                GROUP_SECURITY, "ULTIBIZ_RATE_LIMIT_REQUESTS", "60", false,
                "per IP and path within the window"));
        ALL.add(SettingDefinition.number("rate.limit.window", "The window of the rate limit"
                + " (seconds)", GROUP_SECURITY, "ULTIBIZ_RATE_LIMIT_WINDOW", "60", false, ""));

        // the reports
        ALL.add(SettingDefinition.text("report.dir", "The directory of the reports", GROUP_REPORTS,
                "ULTIBIZ_REPORT_DIR", "reports", false, "the scheduled reports are written here"));
        ALL.add(SettingDefinition.text("dashboard.delivery.webhook", "The delivery webhook",
                GROUP_REPORTS, "ULTIBIZ_DASHBOARD_DELIVERY_WEBHOOK", "", false,
                "the generated report is posted to this URL"));
    }

    public static List<SettingDefinition> all() {
        return List.copyOf(ALL);
    }

    public static SettingDefinition byKey(String key) {
        for (SettingDefinition definition : ALL) {
            if (definition.key().equals(key)) {
                return definition;
            }
        }
        return null;
    }

    private SettingDefinitions() {
    }
}
