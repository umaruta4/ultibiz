package com.ultibiz.core.integration;

import com.ultibiz.core.ai.AiSecretCipher;
import com.ultibiz.core.application.EmailSettingsService;
import com.ultibiz.core.infrastructure.EmailSettingsRepository;
import com.ultibiz.core.mail.EmailGateway;
import com.ultibiz.core.mail.EmailSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The e-mail settings against a real MariaDB: the administrator saves the
 * configuration, the secrets are sealed (never in clear text), the interface
 * only sees "the secret is set" and the gateway is built from the settings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmailSettingsIntegrationTest {

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static EmailSettingsService settings;
    private static long organizationId;

    @BeforeAll
    static void prepareDatabase() {
        String url = System.getProperty("ultibiz.it.jdbcUrl");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "integration tests need -Dultibiz.it.jdbcUrl (skipped)");
        String user = System.getProperty("ultibiz.it.user", "ultibiz");
        String password = System.getProperty("ultibiz.it.password", "");

        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(url, user, password)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(6);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);

        organizationId = ((Number) DSL.using(dataSource, SQLDialect.MARIADB).fetchValue(
                "SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        settings = new EmailSettingsService(new EmailSettingsRepository(jdbc),
                new AiSecretCipher("the-deployment-key-of-the-test"));
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void theSeedConfigurationIsThere() {
        EmailSettings current = settings.load(organizationId);
        assertNotNull(current, "the organisation has a configuration");
        assertEquals("NONE", current.providerName());
        assertFalse(current.isUsable(), "without a transport nothing is usable");
    }

    @Test
    @Order(2)
    void theAdministratorSavesAnApiProvider() {
        settings.save(organizationId, new EmailSettingsService.Form(
                "SENDGRID", "reports@example.com", null, "587", null, null, "SG.the-key",
                null, "us", null, null, true, false), "admin");

        EmailSettings loaded = settings.load(organizationId);
        assertEquals("SENDGRID", loaded.providerName());
        assertEquals("reports@example.com", loaded.fromAddress());
        assertEquals("SG.the-key", loaded.apiKey(), "the key is opened again");
        assertTrue(loaded.isUsable(), "the transport is ready");
    }

    @Test
    @Order(3)
    void theSecretIsNeverStoredInClearText() {
        String stored = jdbc.queryForObject(
                "SELECT api_key_encrypted FROM email_provider_config WHERE organization_id = ?",
                String.class, organizationId);
        assertNotNull(stored);
        assertFalse(stored.contains("SG.the-key"), "the key is not in clear text in the database");
        assertTrue(stored.startsWith("v1:"), "the key is sealed");
    }

    @Test
    @Order(4)
    void theInterfaceOnlySeesThatTheSecretIsSet() {
        EmailSettings masked = settings.masked(organizationId);
        assertEquals("********", masked.apiKey(), "the key is masked");
        assertNull(masked.smtpPassword(), "the empty secret stays empty");
        assertEquals("SENDGRID", masked.providerName());
    }

    @Test
    @Order(5)
    void anEmptySecretKeepsTheStoredOne() {
        settings.save(organizationId, new EmailSettingsService.Form(
                "SENDGRID", "reports@example.com", null, "587", null, null, "",
                null, "us", null, null, true, false), "admin");
        assertEquals("SG.the-key", settings.load(organizationId).apiKey(),
                "the stored key survives an empty field");
    }

    @Test
    @Order(6)
    void theSecretsCanBeRemoved() {
        settings.save(organizationId, new EmailSettingsService.Form(
                "SENDGRID", "reports@example.com", null, "587", null, null, "",
                null, "us", null, null, true, true), "admin");
        assertNull(settings.load(organizationId).apiKey(), "the key is gone");
        assertFalse(settings.load(organizationId).isUsable(), "the transport is not usable");
    }

    @Test
    @Order(7)
    void theGatewayIsBuiltFromTheSettings() {
        settings.save(organizationId, new EmailSettingsService.Form(
                "MAILGUN", "reports@example.com", null, "587", null, null, "key-mg",
                "mg.example.com", "eu", null, null, true, false), "admin");
        EmailGateway gateway = EmailGateway.from(settings.load(organizationId));
        assertTrue(gateway.isEnabled(), "the gateway is ready");
        assertEquals("mailgun", gateway.providerName());
    }

    @Test
    @Order(8)
    void withoutTheDeploymentKeyNoSecretIsStored() {
        EmailSettingsService withoutKey = new EmailSettingsService(
                new EmailSettingsRepository(jdbc), new AiSecretCipher(""));
        assertThrows(IllegalStateException.class, () -> withoutKey.save(organizationId,
                new EmailSettingsService.Form("SENDGRID", "reports@example.com", null, "587", null,
                        null, "SG.another", null, "us", null, null, true, false), "admin"),
                "without the deployment key nothing is stored");
    }
}
