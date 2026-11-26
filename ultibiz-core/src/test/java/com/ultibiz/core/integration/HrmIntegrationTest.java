package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.HrmService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.hrm.ExpensePolicy;
import com.ultibiz.core.hrm.HrmException;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.HrmRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HRM plugin against a real MariaDB, end to end: hire an employee, run
 * the leave flow (working days, overlap, balance), book and approve a
 * timesheet, settle an expense claim, run and export the payroll, and
 * exercise the GDPR export/erasure hooks.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HrmIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static HrmService hrm;
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
        hrm = new HrmService(new HrmRepository(jdbc), new AuditService(new AuditRepository(dsl)));

        employeeId = hrm.hire(scope, "it", "EMP-IT1", "Sari", "Hartono",
                LocalDate.parse("2026-01-15"), "sari@example.com", "+62-812-0000-1111",
                "09.123.456.7-891.000", LocalDate.parse("1995-04-02"));
        jdbc.update("""
                INSERT INTO hr_contract (organization_id, employee_id, contract_no, contract_type,
                    start_date, base_salary, currency, weekly_hours, status)
                VALUES (?, ?, 'CON-IT1', 'PERMANENT', '2026-01-15', 8000000.00, 'IDR', 40.00, 'ACTIVE')
                """, orgId, employeeId);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void theLeaveFlowEnforcesTheRules() {
        // 2026-11-23 (Mon) .. 2026-11-25 (Wed) = 3 working days
        long requestId = hrm.requestLeave(scope, "it", employeeId, "ANNUAL",
                LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-25"), "Family");
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT status FROM hr_leave_request WHERE id = ?", String.class, requestId));
        assertEquals(new BigDecimal("3.0"), jdbc.queryForObject(
                "SELECT days FROM hr_leave_request WHERE id = ?", BigDecimal.class, requestId));

        // an overlapping request is refused while the first is open
        assertThrows(HrmException.class, () -> hrm.requestLeave(scope, "it", employeeId,
                "ANNUAL", LocalDate.parse("2026-11-25"), LocalDate.parse("2026-11-27"), null));

        hrm.decideLeave(scope, "hr", requestId, true);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM hr_leave_request WHERE id = ?", String.class, requestId));

        // a decided request cannot be decided again
        assertThrows(HrmException.class, () -> hrm.decideLeave(scope, "hr", requestId, false));

        // the balance covers only 12 - 3 = 9 more days this year
        assertThrows(HrmException.class, () -> hrm.requestLeave(scope, "it", employeeId,
                "ANNUAL", LocalDate.parse("2026-12-07"), LocalDate.parse("2026-12-31"), null));
    }

    @Test
    @Order(2)
    void theTimesheetFeedsTheApprovedHours() {
        long timesheetId = hrm.bookTimesheet(scope, "it", employeeId,
                LocalDate.now().minusDays(1), "PRJ-DEMO", new BigDecimal("7.5"), true,
                "Feature work");
        assertThrows(HrmException.class, () -> hrm.bookTimesheet(scope, "it", employeeId,
                LocalDate.now().minusDays(1), "PRJ-DEMO", new BigDecimal("1"), true,
                "a duplicate (employee, date, project)"));
        hrm.approveTimesheet(scope, "hr", timesheetId);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM hr_timesheet WHERE id = ?", String.class, timesheetId));
    }

    @Test
    @Order(3)
    void theExpenseClaimSettles() {
        long claimId = hrm.submitExpenseClaim(scope, "it", employeeId, "EXP-IT1", "IDR",
                List.of(new ExpensePolicy.Line(new BigDecimal("250000"), "TRAVEL"),
                        new ExpensePolicy.Line(new BigDecimal("80000"), "MEAL")),
                List.of(LocalDate.now().minusDays(3), LocalDate.now().minusDays(2)),
                List.of("RCP-1", "RCP-2"));
        assertEquals(new BigDecimal("330000.00"), jdbc.queryForObject(
                "SELECT total FROM hr_expense_claim WHERE id = ?", BigDecimal.class, claimId));
        hrm.decideExpense(scope, "hr", claimId, true);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM hr_expense_claim WHERE id = ?", String.class, claimId));
    }

    @Test
    @Order(4)
    void thePayrollRunsAndExports() {
        String period = "2026-11";
        long runId = hrm.runPayroll(scope, "it", period);
        String csv = hrm.exportPayroll(scope, "hr", period);
        assertTrue(csv.contains("EMP-IT1"), "the export lists the employee");
        assertTrue(csv.contains("8000000.00"), "the export carries the contract base");

        // an exported run is closed: re-running and re-exporting are refused
        assertThrows(HrmException.class, () -> hrm.runPayroll(scope, "it", period));
        Number exported = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'HR_PAYROLL_EXPORT'");
        assertTrue(exported.intValue() >= 1, "the export is audited");
    }

    @Test
    @Order(5)
    void theGdprHooksExportAndErase() {
        Map<String, Object> data = hrm.exportPersonalData(scope, employeeId);
        assertEquals("sari@example.com", data.get("personal_email"),
                "the export carries the personal fields");

        hrm.erasePersonalData(scope, "dpo", employeeId);

        Map<String, Object> erased = hrm.exportPersonalData(scope, employeeId);
        assertEquals("Erased", erased.get("first_name"));
        assertEquals(null, erased.get("personal_email"), "the e-mail is anonymised");
        assertEquals(null, erased.get("national_id"), "the national id is anonymised");
        // the payroll line of the employee stays — the books never change
        Number lines = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM hr_payroll_line l JOIN hr_employee e ON e.id = l.employee_id
                WHERE e.id = ?
                """, employeeId);
        assertEquals(1, lines.intValue(), "the financial rows survive the erasure");
    }
}
