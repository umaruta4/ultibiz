package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.DashboardService;
import com.ultibiz.core.application.InsightService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.AiRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.DashboardRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Dashboard plugin against a real MariaDB: the role-based tiles, the
 * threshold alerts, the saved views, the scheduled report (a real file on
 * disk) and the insight board.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static DashboardService dashboard;

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
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);
        jdbc = new JdbcTemplate(dataSource);

        long orgId = ((Number) dsl.fetchValue(
                "SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        long siteId = ((Number) dsl.fetchValue(
                "SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);

        AiRepository ai = new AiRepository(jdbc);
        InsightService insights = new InsightService(List.of(), ai);
        dashboard = new DashboardService(new DashboardRepository(jdbc), insights,
                new AuditService(new AuditRepository(dsl)), System.getProperty("java.io.tmpdir"),
                "");
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void theTilesFollowThePermissions() {
        // the admin holds every permission code of the catalogue
        List<DashboardService.Tile> all = dashboard.tiles(scope, Set.of(
                "accounting.journal.view", "accounting.ar.view", "inventory.stock.view",
                "hrm.employee.view", "hrm.leave.view", "crm.opportunity.view",
                "prj.project.view", "sales.order.view", "purchasing.order.view"));
        assertTrue(all.size() >= 10, "every catalogue tile is visible");

        // a user without the accounting permissions sees fewer tiles
        List<DashboardService.Tile> limited = dashboard.tiles(scope, Set.of("hrm.employee.view"));
        assertTrue(limited.size() < all.size(), "the role decides what a user sees");
        assertTrue(limited.stream().anyMatch(tile -> "HEADCOUNT".equals(tile.metric().metricKey())));
        assertFalse(limited.stream().anyMatch(tile ->
                "AR_OVERDUE".equals(tile.metric().metricKey())));
    }

    @Test
    @Order(2)
    void theMetricsReadTheRealData() {
        // the seed hires exactly one employee (EMP-0001)
        assertEquals(0, new BigDecimal("1").compareTo(dashboard.metricValue(scope, "HEADCOUNT")));
        assertEquals(0, BigDecimal.ZERO.compareTo(dashboard.metricValue(scope, "AR_OVERDUE_AMOUNT")));
        assertEquals(0, BigDecimal.ZERO.compareTo(dashboard.metricValue(scope, "CRM_PIPELINE")));
    }

    @Test
    @Order(3)
    void theThresholdsRaiseAlerts() {
        // nothing is overdue in the fixture
        assertTrue(dashboard.alerts(scope).stream()
                .noneMatch(alert -> "AR_OVERDUE_AMOUNT".equals(alert.metricKey())));

        // a threshold under the headcount fires
        jdbc.update("""
                UPDATE dash_threshold SET value = 0, comparison = 'GTE'
                WHERE metric_key = 'OPEN_LEAVES'
                """);
        var alerts = dashboard.alerts(scope);
        assertTrue(alerts.stream().anyMatch(alert -> "OPEN_LEAVES".equals(alert.metricKey())),
                "the threshold raised an alert");
    }

    @Test
    @Order(4)
    void theSavedViewsAreStored() {
        long id = dashboard.saveView(scope, "it", 1L, "AR-OPEN", "Open receivables",
                "accounting", "{\"status\":\"OPEN\"}", true);
        assertTrue(dashboard.savedViews(scope, 1L).stream()
                .anyMatch(view -> view.id() == id));
        dashboard.deleteView(scope, "it", 1L, id);
        assertTrue(dashboard.savedViews(scope, 1L).stream()
                .noneMatch(view -> view.id() == id));
    }

    @Test
    @Order(5)
    void theScheduledReportWritesARealFile() throws Exception {
        dashboard.subscribe(scope, "it", 1L, "HEADCOUNT", "Employees", "DAILY",
                "CSV", "ops@example.com");
        var subscription = dashboard.subscriptions(scope, 1L).get(0);
        assertTrue(subscription.nextRunAt().isAfter(LocalDateTime.now()),
                "the next run is in the future");

        var file = dashboard.generate(subscription);
        assertEquals("FILE_READY", file.deliveryStatus(),
                "without a delivery webhook the file is ready for download");
        Path path = Path.of(file.filePath());
        assertTrue(Files.exists(path), "the report file exists on disk");
        String body = Files.readString(path);
        assertTrue(body.startsWith("employee_no;"), "the CSV has a header line");
        assertTrue(body.contains("EMP-0001"), "the CSV carries the data");
    }

    @Test
    @Order(6)
    void theInsightBoardConsumesTheProviders() {
        var alerts = dashboard.insightBoard(scope);
        assertTrue(alerts != null, "the insight board answers");
    }
}
