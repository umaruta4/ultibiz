package com.ultibiz.web.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ultibiz.core.infrastructure.SystemSettingRepository;
import com.ultibiz.core.settings.SystemSettingsService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The settings of the deployment: a value of the database wins over the
 * environment variable, a secret is sealed and never sent back to the browser.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "ultibiz.it.jdbcUrl", matches = ".+")
class SystemSettingsServiceTest {

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("ultibiz.it.jdbcUrl"));
        registry.add("spring.datasource.username",
                () -> System.getProperty("ultibiz.it.user", "ultibiz"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("ultibiz.it.password", ""));
        registry.add("ultibiz.tenant.control-database",
                () -> System.getProperty("ultibiz.it.jdbcUrl").replaceAll(".*/([^/?]+).*", "$1"));
        registry.add("ultibiz.tenant.admin-password",
                () -> System.getProperty("ultibiz.it.adminPassword", ""));
        registry.add("ultibiz.tenant.mode", () -> "single");
        registry.add("ultibiz.ai.encryption-key", () -> "the-deployment-key-of-the-test");
        registry.add("ULTIBIZ_AI_ENCRYPTION_KEY", () -> "the-deployment-key-of-the-test");
    }

    @Autowired
    private SystemSettingsService settings;

    @Autowired
    private SystemSettingRepository repository;

    @Test
    void aValueOfTheDatabaseWinsOverTheEnvironment() {
        try {
            settings.save(Map.of("rate.limit.requests", "77"), Set.of(), "tester");
            assertEquals("77", settings.value("rate.limit.requests"));
            assertEquals(77, settings.intValue("rate.limit.requests", 60));
        } finally {
            repository.delete("rate.limit.requests");
        }
    }

    @Test
    void withoutAValueTheFallbackOfTheRegistryIsUsed() {
        // the environment variable is not set in the test: the default of the
        // registry is in force (the value of the environment is the fallback)
        assertEquals("single", settings.value("tenant.mode"));
        assertEquals(60, settings.intValue("rate.limit.window", 60));
    }

    @Test
    void aSecretIsSealedAndNeverShown() {
        try {
            settings.save(Map.of("email.smtp.password", "the-secret-of-the-test"), Set.of(),
                    "tester");
            assertTrue(settings.hasSecret("email.smtp.password"), "the secret is stored");
            assertEquals("", settings.formValue("email.smtp.password"),
                    "the form never shows the secret");
            assertEquals("the-secret-of-the-test", settings.value("email.smtp.password"),
                    "the service reads the secret");
            String stored = repository.findValue("email.smtp.password");
            assertNotEquals("the-secret-of-the-test", stored, "the secret is not in clear text");
            assertTrue(stored.startsWith("v1:"), "the secret is sealed: " + stored);
        } finally {
            repository.delete("email.smtp.password");
        }
        assertFalse(settings.hasSecret("email.smtp.password"));
    }

    @Test
    void aTickedBoxRemovesTheSecret() {
        settings.save(Map.of("email.smtp.password", "the-secret-of-the-test"), Set.of(), "tester");
        settings.save(Map.of(), Set.of("email.smtp.password"), "tester");
        assertFalse(settings.hasSecret("email.smtp.password"), "the secret is removed");
        assertFalse(settings.formValue("email.smtp.password").contains("the-secret"));
    }
}
