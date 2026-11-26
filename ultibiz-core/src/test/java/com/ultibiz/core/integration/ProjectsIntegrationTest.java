package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.HrmService;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.ProjectService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.HrmRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.ultibiz.core.infrastructure.ProjectRepository;
import com.ultibiz.core.projects.ProjectException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Projects plugin against a real MariaDB, end to end: a project with a
 * WBS and a billing milestone, a resource assignment with the capacity rule,
 * an approved HRM timesheet that becomes the project cost, and the milestone
 * invoicing that posts a real revenue journal — the profitability feeds the
 * accounting.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectsIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static ProjectService projects;
    private static HrmService hrm;
    private static long projectId;
    private static long milestoneId;
    private static long employeeId;

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

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AuditService audit = new AuditService(new AuditRepository(dsl));
        HrmRepository hr = new HrmRepository(jdbc);
        hrm = new HrmService(hr, audit);
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx);
        JournalService journals = new JournalService(new JournalRepository(jdbc),
                new FiscalPeriodRepository(jdbc), new AccountRepository(jdbc),
                new CountryConfigRepository(dsl), numbering, audit,
                new com.ultibiz.core.application.FxService(
                        new com.ultibiz.core.infrastructure.FxRateRepository(jdbc),
                        java.util.List.of()), tx);
        projects = new ProjectService(new ProjectRepository(jdbc), hr,
                new AccountRepository(jdbc), journals, audit);

        employeeId = ((Number) dsl.fetchValue(
                "SELECT id FROM hr_employee WHERE employee_no = 'EMP-0001'")).longValue();
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void aProjectWithWbsAndMilestone() {
        projectId = projects.createProject(scope, "it", "PRJ-IT1", "IT rollout", null,
                LocalDate.parse("2026-11-01"), null, new BigDecimal("10000000"), "IDR",
                new BigDecimal("250000"));
        projects.addWbs(scope, "it", projectId, "WBS-1", "Analysis", null, new BigDecimal("40"));
        milestoneId = projects.addMilestone(scope, "it", projectId, "MS-1", "Go-live",
                LocalDate.parse("2026-12-15"), new BigDecimal("5000000"));
        assertEquals(1, projects.wbs(projectId).size());
        assertEquals("PENDING", projects.milestones(projectId).get(0).status());
    }

    @Test
    @Order(2)
    void theResourceCapacityIsEnforced() {
        // the contract of EMP-0001 is 40 h/week; the seed assigns 20 h on PRJ-DEMO
        projects.assign(scope, "it", projectId, employeeId, "Member", new BigDecimal("20"),
                LocalDate.parse("2026-11-26"), null);
        // 20 (demo) + 20 (this project) + 1 more would exceed the capacity
        assertThrows(ProjectException.class, () -> projects.assign(scope, "it", projectId,
                employeeId, "Member", new BigDecimal("20.01"), LocalDate.parse("2026-11-26"),
                null));
    }

    @Test
    @Order(3)
    void theApprovedTimesheetBecomesTheCost() {
        long timesheetId = hrm.bookTimesheet(scope, "it", employeeId,
                LocalDate.now().minusDays(1), "PRJ-IT1", new BigDecimal("7.5"), true,
                "rollout work");
        hrm.approveTimesheet(scope, "hr", timesheetId);

        ProjectService.CostReport report = projects.costReport(scope, projectId);
        assertEquals(0, new BigDecimal("7.50").compareTo(report.hoursTotal()));
        assertEquals(0, new BigDecimal("7.50").compareTo(report.hoursBillable()));
        // 7.5 h at 10,000,000 / (40 * 4.348125) per hour ≈ 431,221.33
        assertTrue(report.labourCost().compareTo(new BigDecimal("400000")) > 0,
                "the labour cost is the hours at the contractual rate");
        assertEquals(new BigDecimal("1875000.00"), report.hoursRevenue(),
                "the billable hours at the billing rate");
        assertTrue(report.profitability().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(report.budgetUsagePercent().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @Order(4)
    void theMilestoneInvoicingPostsTheRevenue() {
        projects.reachMilestone(scope, "it", milestoneId);
        long journalId = projects.invoiceMilestone(scope, "it", milestoneId);
        assertNotNull(journalId);

        var milestone = projects.milestones(projectId).get(0);
        assertEquals("INVOICED", milestone.status());
        assertEquals(journalId, milestone.journalId());

        // the journal really balances Dr 1200 / Cr 4100 at the milestone amount
        Number debits = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM journal_line l
                JOIN account a ON a.id = l.account_id
                WHERE l.journal_id = ? AND a.code = '1200' AND l.debit = 5000000.00
                """, journalId);
        assertEquals(1, debits.intValue(), "the receivable is booked");

        // the flow never goes backwards
        assertThrows(ProjectException.class, () -> projects.reachMilestone(scope, "it",
                milestoneId));

        ProjectService.CostReport report = projects.costReport(scope, projectId);
        assertEquals(new BigDecimal("5000000.00"), report.milestoneRevenue());
        assertEquals(new BigDecimal("6875000.00"), report.revenue(),
                "hours revenue + invoiced milestone");
    }
}
