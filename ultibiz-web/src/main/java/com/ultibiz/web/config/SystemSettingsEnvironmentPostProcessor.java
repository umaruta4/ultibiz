package com.ultibiz.web.config;

import com.ultibiz.core.ai.AiSecretCipher;
import com.ultibiz.core.settings.SettingDefinition;
import com.ultibiz.core.settings.SettingDefinitions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * The settings of the database are the properties of the start: they win over
 * the environment variables, so a value that the administrator changed in the
 * interface is in force after a restart.
 *
 * The post processor never stops the start — when the database is not
 * reachable the environment variables stay in force.
 */
public class SystemSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE = "ultibizSystemSettings";

    /** The property of the application for the items without an environment variable. */
    private static final Map<String, String> PROPERTIES = Map.ofEntries(
            Map.entry("sso.enabled", "ultibiz.sso.enabled"),
            Map.entry("sso.issuer", "ultibiz.sso.issuer"),
            Map.entry("sso.client.id", "ultibiz.sso.client-id"),
            Map.entry("sso.client.secret", "ultibiz.sso.client-secret"),
            Map.entry("sso.redirect.uri", "ultibiz.sso.redirect-uri"),
            Map.entry("sso.auto.provision", "ultibiz.sso.auto-provision"),
            Map.entry("sso.organization", "ultibiz.sso.default-organization"),
            Map.entry("sso.role", "ultibiz.sso.default-role"),
            Map.entry("sso.admin.roles", "ultibiz.sso.admin-roles"),
            Map.entry("email.provider", "ultibiz.mail.provider"),
            Map.entry("email.smtp.host", "ultibiz.mail.smtp.host"),
            Map.entry("email.smtp.port", "ultibiz.mail.smtp.port"),
            Map.entry("email.smtp.user", "ultibiz.mail.smtp.user"),
            Map.entry("email.smtp.password", "ultibiz.mail.smtp.password"),
            Map.entry("email.from", "ultibiz.mail.from"),
            Map.entry("email.mailgun.domain", "ultibiz.mail.mailgun.domain"),
            Map.entry("email.mailgun.region", "ultibiz.mail.mailgun.region"),
            Map.entry("email.webhook.url", "ultibiz.mail.webhook.url"),
            Map.entry("email.webhook.secret", "ultibiz.mail.webhook.secret"),
            Map.entry("exchange.webhook.secret", "ultibiz.exchange.webhook-secret"),
            Map.entry("rate.limit.requests", "ultibiz.rate-limit.requests"),
            Map.entry("rate.limit.window", "ultibiz.rate-limit.window-seconds"),
            Map.entry("report.dir", "ultibiz.dashboard.report-dir"),
            Map.entry("dashboard.delivery.webhook", "ultibiz.dashboard.delivery-webhook"),
            Map.entry("tenant.mode", "ultibiz.tenant.mode"),
            Map.entry("db.host", "ultibiz.tenant.admin-host"),
            Map.entry("db.port", "ultibiz.tenant.admin-port"),
            Map.entry("db.name", "ultibiz.tenant.control-database"),
            Map.entry("db.user", "ultibiz.tenant.application-user"),
            Map.entry("db.password", "ultibiz.tenant.application-password"));

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Map<String, String> stored = read(environment);
        if (stored.isEmpty()) {
            return;
        }
        Properties properties = new Properties();
        String sealingKey = stored.getOrDefault(SystemSettingsServiceKeys.SEALING_KEY, "");
        for (SettingDefinition definition : SettingDefinitions.all()) {
            String value = stored.get(definition.key());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (definition.environmentVariable() != null) {
                properties.setProperty(definition.environmentVariable(), value);
            }
            String property = PROPERTIES.get(definition.key());
            if (property != null) {
                properties.setProperty(property, value);
            }
        }
        if (!sealingKey.isBlank()) {
            properties.setProperty("ultibiz.ai.encryption-key", sealingKey);
        }
        if (ssoActive(stored)) {
            String issuer = stored.getOrDefault("sso.issuer", "");
            String clientId = stored.getOrDefault("sso.client.id", "");
            String secret = stored.getOrDefault("sso.client.secret", "");
            String redirect = stored.getOrDefault("sso.redirect.uri", "");
            if (!issuer.isBlank() && !clientId.isBlank() && !secret.isBlank()) {
                properties.setProperty("spring.security.oauth2.client.registration.keycloak.client-id",
                        clientId);
                properties.setProperty("spring.security.oauth2.client.registration.keycloak.client-secret",
                        secret);
                properties.setProperty("spring.security.oauth2.client.registration.keycloak.scope",
                        "openid,profile,email");
                properties.setProperty("spring.security.oauth2.client.registration.keycloak"
                        + ".authorization-grant-type", "authorization_code");
                properties.setProperty("spring.security.oauth2.client.registration.keycloak.client-name",
                        "Keycloak");
                if (!redirect.isBlank()) {
                    properties.setProperty("spring.security.oauth2.client.registration.keycloak"
                            + ".redirect-uri", redirect);
                }
                properties.setProperty("spring.security.oauth2.client.provider.keycloak.issuer-uri",
                        issuer);
                properties.setProperty("server.forward-headers-strategy", "framework");
            }
        }
        environment.getPropertySources().addFirst(new PropertiesPropertySource(SOURCE, properties));
    }

    /** The single sign-on is active when the setting says so or the profile is switched on. */
    private boolean ssoActive(Map<String, String> stored) {
        String value = stored.get("sso.enabled");
        if (value != null && !value.isBlank()) {
            return "true".equalsIgnoreCase(value.trim());
        }
        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        return profiles != null && profiles.contains("sso");
    }

    /** Reads the settings of the control database; an empty map when it is not reachable. */
    private Map<String, String> read(ConfigurableEnvironment environment) {
        Map<String, String> result = new LinkedHashMap<>();
        // the same data source as the application: the settings of the control
        // database are read from the database the application itself uses
        String url = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        if (url == null || url.isBlank()) {
            String host = environment.getProperty("ULTIBIZ_DB_HOST", "localhost");
            String port = environment.getProperty("ULTIBIZ_DB_PORT", "3311");
            String name = environment.getProperty("ULTIBIZ_DB_NAME", "ultibiz");
            user = environment.getProperty("ULTIBIZ_DB_USER", "ultibiz");
            password = environment.getProperty("ULTIBIZ_DB_PASSWORD", "");
            url = "jdbc:mariadb://" + host + ":" + port + "/" + name;
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT setting_key, value_text, secret FROM system_setting")) {
            Map<String, String> sealed = new LinkedHashMap<>();
            Map<String, Boolean> secret = new LinkedHashMap<>();
            while (rows.next()) {
                String key = rows.getString(1);
                String value = rows.getString(2);
                sealed.put(key, value);
                secret.put(key, rows.getInt(3) == 1);
            }
            AiSecretCipher bootstrap = new AiSecretCipher(
                    environment.getProperty("ULTIBIZ_AI_ENCRYPTION_KEY", ""));
            String sealingKey = environment.getProperty("ULTIBIZ_AI_ENCRYPTION_KEY", "");
            String storedKey = sealed.get(SystemSettingsServiceKeys.SEALING_KEY);
            if (storedKey != null && !storedKey.isBlank()) {
                String opened = bootstrap.decrypt(storedKey);
                if (opened != null && !opened.isBlank()) {
                    sealingKey = opened;
                }
            }
            AiSecretCipher cipher = new AiSecretCipher(sealingKey);
            for (Map.Entry<String, String> entry : sealed.entrySet()) {
                String value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(secret.get(entry.getKey()))) {
                    String opened = cipher.decrypt(value);
                    result.put(entry.getKey(), opened == null ? "" : opened);
                } else {
                    result.put(entry.getKey(), value);
                }
            }
        } catch (Exception ex) {
            // the settings of the database are optional: the environment variables stay in force
            return new LinkedHashMap<>();
        }
        return result;
    }
}
