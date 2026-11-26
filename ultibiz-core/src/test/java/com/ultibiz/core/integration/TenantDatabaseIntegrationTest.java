package com.ultibiz.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ultibiz.core.infrastructure.TenantRegistryRepository;
import com.ultibiz.core.tenant.TenantContext;
import com.ultibiz.core.tenant.TenantDataSourceFactory;
import com.ultibiz.core.tenant.TenantProvisionService;
import com.ultibiz.core.tenant.TenantRoutingDataSource;
import com.ultibiz.core.tenant.TenantService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The multi-tenant mode against a real MariaDB: a tenant database is created,
 * the migrations run on it, the identity is copied and the routing sends the
 * queries of a tenant to its own database — the row written in the tenant is
 * **not** in the control database.
 *
 * <p>The creation needs the administrator of the MariaDB server, so this test
 * runs only when `-Dultibiz.it.adminPassword` is given.
 */
class TenantDatabaseIntegrationTest {

    private static final String TENANT_DATABASE = "ultibiz_tenant_it";

    private static String host;
    private static String port;
    private static String adminPassword;
    private static String adminUser;
    private static String controlDatabase;
    private static HikariDataSource control;
    private static TenantProvisionService provision;
    private static TenantService tenants;

    @BeforeAll
    static void prepare() throws Exception {
        String url = System.getProperty("ultibiz.it.jdbcUrl");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no test database (skipped)");
        adminPassword = System.getProperty("ultibiz.it.adminPassword");
        Assumptions.assumeTrue(adminPassword != null && !adminPassword.isBlank(),
                "the tenant test needs -Dultibiz.it.adminPassword (skipped)");
        adminUser = System.getProperty("ultibiz.it.adminUser", "root");
        String user = System.getProperty("ultibiz.it.user", "ultibiz");
        String password = System.getProperty("ultibiz.it.password", "");

        // jdbc:mariadb://host:port/database
        String rest = url.substring(url.indexOf("//") + 2);
        host = rest.substring(0, rest.indexOf(':'));
        port = rest.substring(rest.indexOf(':') + 1, rest.indexOf('/'));
        controlDatabase = rest.substring(rest.indexOf('/') + 1).split("\\?")[0];

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        control = new HikariDataSource(config);

        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(url, user, password)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();

        JdbcTemplate controlJdbc = new JdbcTemplate(control);
        TenantRegistryRepository registry = new TenantRegistryRepository(controlJdbc);
        tenants = new TenantService(registry, "database");
        provision = new TenantProvisionService(registry, tenants, adminUser, adminPassword,
                host, port, controlDatabase, user, password);
        dropTenantDatabase();
    }

    @AfterAll
    static void tearDown() {
        dropTenantDatabase();
        if (control != null) {
            control.close();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void dropTenantDatabase() {
        try (var connection = DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/information_schema",
                adminUser, adminPassword);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + TENANT_DATABASE + "`");
        } catch (Exception ex) {
            throw new IllegalStateException("The test tenant could not be dropped", ex);
        }
    }

    @Test
    void theTenantDatabaseIsProvisionedAndTheQueriesAreIsolated() {
        // -- the provisioning: the database, the migrations, the identity
        TenantProvisionService.Result result = provision.provision("ACME", TENANT_DATABASE, "test");
        assertEquals("ACTIVE", result.status());
        assertTrue(tenants.isPerTenantDatabase());
        long organizationId = tenants.all().get(0).organizationId();
        assertEquals(TENANT_DATABASE, tenants.databaseOf(organizationId));

        // -- the routing data source
        TenantDataSourceFactory factory = new TenantDataSourceFactory(host, port,
                System.getProperty("ultibiz.it.user", "ultibiz"),
                System.getProperty("ultibiz.it.password", ""), 4);
        TenantRoutingDataSource routing = new TenantRoutingDataSource(control, factory, true);
        JdbcTemplate routingJdbc = new JdbcTemplate(routing);

        // -- without a tenant: the control database
        assertEquals(controlDatabase, routingJdbc.queryForObject("SELECT DATABASE()", String.class));

        // -- in the tenant: its own database
        TenantContext.set(TENANT_DATABASE);
        assertEquals(TENANT_DATABASE, routingJdbc.queryForObject("SELECT DATABASE()", String.class));

        // -- a row in the tenant is not in the control database
        routingJdbc.update("INSERT INTO uom (code, name, decimal_places) VALUES ('IT_TEN','tenant',0)");
        TenantContext.clear();
        assertEquals(0, routingJdbc.queryForObject(
                "SELECT COUNT(*) FROM uom WHERE code = 'IT_TEN'", Integer.class).intValue());

        // -- the row of the tenant is in its own database
        TenantContext.set(TENANT_DATABASE);
        assertEquals(1, routingJdbc.queryForObject(
                "SELECT COUNT(*) FROM uom WHERE code = 'IT_TEN'", Integer.class).intValue());

        // -- the identity was copied (the organisation of the tenant is there)
        Long count = routingJdbc.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE code = 'ACME'", Long.class);
        assertNotNull(count);
        assertTrue(count >= 1, "the organisation must be copied into the tenant database");

        // -- a suspended tenant falls back to the control database
        provision.setStatus("ACME", "SUSPENDED", "test");
        TenantContext.clear();
        assertNull(tenants.databaseOf(tenants.all().get(0).organizationId()));
    }
}
