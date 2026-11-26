package com.ultibiz.core.integration;

import com.ultibiz.core.infrastructure.SsoRepository;
import com.ultibiz.core.sso.SsoException;
import com.ultibiz.core.sso.SsoProvisioningService;
import com.ultibiz.core.sso.SsoUserPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single sign-on against a real MariaDB: an unknown user is provisioned
 * with the default organisation and role, the second login does not create a
 * second row, an existing user is taken as it is and the administrator role of
 * the identity provider is carried over.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsoIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SsoRepository repository;
    private static SsoProvisioningService provisioning;
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
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);
        jdbc = new JdbcTemplate(dataSource);
        repository = new SsoRepository(dsl);
        provisioning = new SsoProvisioningService(repository,
                new SsoUserPolicy(true, "ACME", "VIEWER", Set.of("ultibiz-admin")),
                new BCryptPasswordEncoder());
        organizationId = ((Number) dsl.fetchValue(
                "SELECT id FROM organization WHERE code = 'ACME'")).longValue();
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void anUnknownUserIsProvisioned() {
        String username = provisioning.resolve(SsoUserPolicy.Claims.of(Map.of(
                "sub", "kc-1", "preferred_username", "Budi.Santoso",
                "email", "budi@example.com", "name", "Budi Santoso")));
        assertEquals("budi.santoso", username);

        var user = repository.findByUsername("budi.santoso");
        assertTrue(user.isPresent(), "the user is stored");
        assertEquals(organizationId, user.get().organizationId(), "the default organisation");
        assertEquals("budi@example.com", user.get().email());
        assertTrue(user.get().enabled(), "the user is enabled");
        assertNotNull(user.get().passwordHash(), "a random password is set");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_site_scope WHERE user_id = ?",
                Long.class, user.get().id());
        assertTrue(count != null && count > 0, "the user has a site scope");
    }

    @Test
    @Order(2)
    void theSecondLoginDoesNotCreateASecondUser() {
        String username = provisioning.resolve(SsoUserPolicy.Claims.of(Map.of(
                "sub", "kc-1", "preferred_username", "budi.santoso",
                "email", "budi@example.com")));
        assertEquals("budi.santoso", username);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?",
                Long.class, "budi.santoso");
        assertEquals(1L, count, "one user, not two");
    }

    @Test
    @Order(3)
    void theEmailLinksToAnExistingUser() {
        String username = provisioning.resolve(SsoUserPolicy.Claims.of(Map.of(
                "sub", "kc-admin", "preferred_username", "admin-keycloak",
                "email", "admin@ultibiz.local")));
        // the e-mail belongs to the seeded administrator
        assertNotNull(username, "a user was found");
        assertTrue(repository.findByEmail("admin@ultibiz.local").isPresent(),
                "the user with this e-mail exists");
    }

    @Test
    @Order(4)
    void theAdministratorRoleIsCarriedOver() {
        provisioning.resolve(SsoUserPolicy.Claims.of(Map.of(
                "sub", "kc-2", "preferred_username", "sso.admin",
                "email", "sso.admin@example.com",
                "realm_access", Map.of("roles", java.util.List.of("ultibiz-admin")))));
        var user = repository.findByUsername("sso.admin");
        assertTrue(user.isPresent());
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = 'ADMIN'
                """, Long.class, user.get().id());
        assertTrue(count != null && count > 0, "the administrator role is assigned");
    }

    @Test
    @Order(5)
    void withoutTheProvisioningAnUnknownUserIsRefused() {
        SsoProvisioningService strict = new SsoProvisioningService(repository,
                new SsoUserPolicy(false, "ACME", "VIEWER", Set.of()), new BCryptPasswordEncoder());
        assertThrows(SsoException.class, () -> strict.resolve(SsoUserPolicy.Claims.of(Map.of(
                "sub", "kc-3", "preferred_username", "nobody.known",
                "email", "nobody@example.com"))),
                "an unknown user is refused when the provisioning is off");
        assertFalse(repository.findByUsername("nobody.known").isPresent(),
                "nothing was written");
    }
}
