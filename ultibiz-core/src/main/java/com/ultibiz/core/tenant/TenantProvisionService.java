package com.ultibiz.core.tenant;

import com.ultibiz.core.infrastructure.TenantRegistryRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Provisions the database of a tenant: the database is created, the
 * migrations run, the demo data is removed, the identity (the organisation,
 * the users, the roles) is copied from the control database and the tenant is
 * registered.
 *
 * <p>The creation needs the administrator of the MariaDB server — the
 * application user may not create a database.
 */
@Service
public class TenantProvisionService {

    private static final Logger LOG = LoggerFactory.getLogger(TenantProvisionService.class);

    /** The tables of the identity — they are copied, not truncated. */
    private static final List<String> IDENTITY_TABLES = List.of(
            "organization", "role", "permission", "role_permission", "app_user",
            "user_role", "user_site_scope", "site");

    /** The reference data (the countries) — it is copied, not truncated. */
    private static final List<String> REFERENCE_TABLES = List.of("country");

    private final TenantRegistryRepository registry;
    private final TenantService tenants;
    private final String adminUser;
    private final String adminPassword;
    private final String adminHost;
    private final String adminPort;
    private final String controlDatabase;
    private final String applicationUser;
    private final String applicationPassword;

    public TenantProvisionService(TenantRegistryRepository registry, TenantService tenants,
                                  @Value("${ultibiz.tenant.admin-user:}") String adminUser,
                                  @Value("${ultibiz.tenant.admin-password:}") String adminPassword,
                                  @Value("${ultibiz.tenant.admin-host:127.0.0.1}") String adminHost,
                                  @Value("${ultibiz.tenant.admin-port:3311}") String adminPort,
                                  @Value("${ultibiz.tenant.control-database:ultibiz}") String controlDatabase,
                                  @Value("${ultibiz.tenant.application-user:ultibiz}") String applicationUser,
                                  @Value("${ultibiz.tenant.application-password:}") String applicationPassword) {
        this.registry = registry;
        this.tenants = tenants;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.adminHost = adminHost;
        this.adminPort = adminPort;
        this.controlDatabase = controlDatabase;
        this.applicationUser = applicationUser;
        this.applicationPassword = applicationPassword;
    }

    /** The result of a provisioning. */
    public record Result(String databaseName, String organizationCode, String status, String detail) {
    }

    /**
     * Provisions the database of an organisation.
     *
     * @param organizationCode the code of the organisation (it must exist in the control database)
     * @param databaseName the name of the database
     */
    public Result provision(String organizationCode, String databaseName, String actor) {
        if (adminUser == null || adminUser.isBlank() || adminPassword == null
                || adminPassword.isBlank()) {
            throw new IllegalStateException("The administrator of the database is not configured"
                    + " (ultibiz.tenant.admin-user / ultibiz.tenant.admin-password)");
        }
        if (databaseName == null || !databaseName.matches("[A-Za-z0-9_]{3,60}")) {
            throw new IllegalArgumentException("The name of the database is not usable: "
                    + databaseName);
        }
        Long organizationId = organizationIdOf(organizationCode);
        if (organizationId == null) {
            throw new IllegalArgumentException("The organisation " + organizationCode
                    + " does not exist in the control database");
        }
        try (Connection admin = adminConnection(controlDatabase)) {
            execute(admin, "CREATE DATABASE IF NOT EXISTS `" + databaseName
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            if (applicationUser != null && !applicationUser.isBlank()) {
                // the application user must be able to connect to the tenant database
                execute(admin, "GRANT ALL PRIVILEGES ON `" + databaseName + "`.* TO '"
                        + applicationUser.replace("'", "") + "'@'%'");
                execute(admin, "FLUSH PRIVILEGES");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("The database " + databaseName + " could not be created: "
                    + ex.getMessage(), ex);
        }
        migrate(databaseName);
        cleanDemoData(databaseName);
        copyIdentity(databaseName, organizationId);
        registry.register(new Tenant(0, organizationId, organizationCode, databaseName, "ACTIVE"),
                actor);
        tenants.invalidate(organizationId);
        LOG.info("The tenant {} was provisioned in the database {}", organizationCode, databaseName);
        return new Result(databaseName, organizationCode, "ACTIVE",
                "The database is ready and the identity is copied");
    }

    /** The state of a tenant (the registration only). */
    public void setStatus(String organizationCode, String status, String actor) {
        Long organizationId = organizationIdOf(organizationCode);
        if (organizationId == null) {
            throw new IllegalArgumentException("The organisation " + organizationCode + " is unknown");
        }
        registry.updateStatus(organizationId, status, actor);
        tenants.invalidate(organizationId);
    }

    /** The migrations run on the new database. */
    private void migrate(String databaseName) {
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource("jdbc:mariadb://" + adminHost + ":" + adminPort + "/" + databaseName,
                        adminUser, adminPassword)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    /** The demo data of the migration is removed — a tenant starts empty. */
    private void cleanDemoData(String databaseName) {
        List<String> tables = new ArrayList<>();
        try (Connection admin = adminConnection(databaseName);
             Statement statement = admin.createStatement()) {
            try (var rs = statement.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                    """)) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    if (!IDENTITY_TABLES.contains(table) && !REFERENCE_TABLES.contains(table)
                            && !table.startsWith("flyway_")) {
                        tables.add(table);
                    }
                }
            }
            if (!tables.isEmpty()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : tables) {
                    statement.execute("TRUNCATE TABLE `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("The demo data of " + databaseName
                    + " could not be removed: " + ex.getMessage(), ex);
        }
    }

    /** The identity of the control database is copied into the tenant database. */
    private void copyIdentity(String databaseName, long organizationId) {
        try (Connection admin = adminConnection(controlDatabase)) {
            List<String> tables = new ArrayList<>(REFERENCE_TABLES);
            tables.addAll(IDENTITY_TABLES);
            for (String table : tables) {
                String condition = switch (table) {
                    case "organization" -> " WHERE id = " + organizationId;
                    case "site", "app_user" -> " WHERE organization_id = " + organizationId;
                    case "role" -> " WHERE organization_id IS NULL OR organization_id = " + organizationId;
                    case "role_permission" -> " WHERE role_id IN (SELECT id FROM role WHERE"
                            + " organization_id IS NULL OR organization_id = " + organizationId + ")";
                    case "user_role" -> " WHERE user_id IN (SELECT id FROM app_user WHERE"
                            + " organization_id = " + organizationId + ")";
                    case "user_site_scope" -> " WHERE user_id IN (SELECT id FROM app_user WHERE"
                            + " organization_id = " + organizationId + ")";
                    default -> "";   // the reference data comes as it is
                };
                execute(admin, "INSERT IGNORE INTO `" + databaseName + "`.`" + table + "`"
                        + " SELECT * FROM `" + controlDatabase + "`.`" + table + "`" + condition);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("The identity could not be copied into " + databaseName
                    + ": " + ex.getMessage(), ex);
        }
    }

    private Long organizationIdOf(String organizationCode) {
        try (Connection admin = adminConnection(controlDatabase);
             Statement statement = admin.createStatement();
             var rs = statement.executeQuery("SELECT id FROM organization WHERE code = '"
                     + organizationCode.replace("'", "") + "'")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return null;
        } catch (SQLException ex) {
            throw new IllegalStateException("The organisation could not be read: " + ex.getMessage(),
                    ex);
        }
    }

    private Connection adminConnection(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://" + adminHost + ":" + adminPort + "/"
                + database, adminUser, adminPassword);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
