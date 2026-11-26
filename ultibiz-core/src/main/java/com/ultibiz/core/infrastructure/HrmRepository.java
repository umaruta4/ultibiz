package com.ultibiz.core.infrastructure;

import com.ultibiz.core.hrm.LeavePolicy;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The HRM persistence: employees, contracts, leave, timesheets, payroll, expenses. */
@Repository
public class HrmRepository {

    private final JdbcTemplate jdbc;

    public HrmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Employee(long id, long organizationId, long siteId, String employeeNo,
                           Long userId, Long positionId, Long costCentreId,
                           String firstName, String lastName, String personalEmail,
                           String phone, String nationalId, LocalDate birthDate,
                           LocalDate hireDate, String status) {
        public String displayName() {
            return firstName + " " + lastName;
        }
    }

    public record Contract(long id, long employeeId, String contractNo, String type,
                           LocalDate startDate, LocalDate endDate, BigDecimal baseSalary,
                           String currency, BigDecimal weeklyHours, String status) {
    }

    public record LeaveType(long id, String code, String name, BigDecimal annualAllowance,
                            boolean paid) {
    }

    public record LeaveRequest(long id, long employeeId, long leaveTypeId, LocalDate startDate,
                               LocalDate endDate, BigDecimal days, String reason, String status,
                               String decidedBy) {
    }

    public record Timesheet(long id, long employeeId, LocalDate workDate, String projectCode,
                            BigDecimal hours, boolean billable, String description, String status) {
    }

    public record ExpenseClaim(long id, long employeeId, String claimNo, BigDecimal total,
                               String currency, String status) {
    }

    public record ExpenseLine(long id, long claimId, LocalDate expenseDate, String category,
                              BigDecimal amount, String receiptRef) {
    }

    public record Incident(long id, long siteId, Long employeeId, LocalDate incidentDate,
                           String category, String severity, String description, String status) {
    }

    public record PayrollRun(long id, String period, String status) {
    }

    // ------------------------------------------------------------------ employees

    public long createEmployee(long organizationId, long siteId, String employeeNo, Long userId,
                               String firstName, String lastName, String personalEmail,
                               String phone, String nationalId, LocalDate birthDate,
                               LocalDate hireDate, Long positionId, Long costCentreId) {
        jdbc.update("""
                INSERT INTO hr_employee (organization_id, site_id, employee_no, user_id,
                    position_id, cost_centre_id, first_name, last_name, personal_email, phone,
                    national_id, birth_date, hire_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, siteId, employeeNo, userId, positionId, costCentreId,
                firstName, lastName, personalEmail, phone, nationalId,
                birthDate == null ? null : Date.valueOf(birthDate), Date.valueOf(hireDate));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Employee> employee(long organizationId, long employeeId) {
        List<Employee> rows = jdbc.query("""
                SELECT * FROM hr_employee
                WHERE organization_id = ? AND id = ? AND deleted_at IS NULL
                """, (rs, n) -> mapEmployee(rs), organizationId, employeeId);
        return rows.stream().findFirst();
    }

    public List<Employee> employees(long organizationId) {
        return jdbc.query("""
                SELECT * FROM hr_employee
                WHERE organization_id = ? AND deleted_at IS NULL
                ORDER BY employee_no
                """, (rs, n) -> mapEmployee(rs), organizationId);
    }

    private Employee mapEmployee(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date birth = rs.getDate("birth_date");
        return new Employee(id(rs.getObject("id")), id(rs.getObject("organization_id")),
                id(rs.getObject("site_id")), rs.getString("employee_no"),
                rs.getObject("user_id") == null ? null : id(rs.getObject("user_id")),
                rs.getObject("position_id") == null ? null : id(rs.getObject("position_id")),
                rs.getObject("cost_centre_id") == null ? null : id(rs.getObject("cost_centre_id")),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("personal_email"), rs.getString("phone"),
                rs.getString("national_id"), birth == null ? null : birth.toLocalDate(),
                rs.getDate("hire_date").toLocalDate(), rs.getString("status"));
    }

    /** The GDPR export: every personal field of one employee. */
    public java.util.Map<String, Object> personalData(long organizationId, long employeeId) {
        return jdbc.queryForMap("""
                SELECT employee_no, first_name, last_name, personal_email, phone, national_id,
                       birth_date, hire_date, status FROM hr_employee
                WHERE organization_id = ? AND id = ?
                """, organizationId, employeeId);
    }

    /** The GDPR erasure: anonymise the personal fields, keep the financial rows. */
    public void anonymise(long organizationId, long employeeId) {
        jdbc.update("""
                UPDATE hr_employee SET first_name = 'Erased', last_name = CONCAT('Employee ', id),
                    personal_email = NULL, phone = NULL, national_id = NULL, birth_date = NULL,
                    status = 'INACTIVE'
                WHERE organization_id = ? AND id = ?
                """, organizationId, employeeId);
    }

    public void logErasure(long organizationId, long employeeId, String requestedBy) {
        jdbc.update("""
                INSERT INTO hr_erasure (organization_id, employee_id, requested_by, completed_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))
                """, organizationId, employeeId, requestedBy);
    }

    // ------------------------------------------------------------------ contracts

    public long createContract(long organizationId, long employeeId, String contractNo,
                               String type, LocalDate startDate, LocalDate endDate,
                               BigDecimal baseSalary, String currency, BigDecimal weeklyHours,
                               String status) {
        jdbc.update("""
                INSERT INTO hr_contract (organization_id, employee_id, contract_no, contract_type,
                    start_date, end_date, base_salary, currency, weekly_hours, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, employeeId, contractNo, type, Date.valueOf(startDate),
                endDate == null ? null : Date.valueOf(endDate), baseSalary, currency,
                weeklyHours, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Contract> activeContract(long employeeId) {
        List<Contract> rows = jdbc.query("""
                SELECT * FROM hr_contract WHERE employee_id = ? AND status = 'ACTIVE'
                ORDER BY start_date DESC
                """, (rs, n) -> new Contract(id(rs.getObject("id")), id(rs.getObject("employee_id")),
                        rs.getString("contract_no"), rs.getString("contract_type"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate(),
                        rs.getBigDecimal("base_salary"), rs.getString("currency"),
                        rs.getBigDecimal("weekly_hours"), rs.getString("status")), employeeId);
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------ leave

    public List<LeaveType> leaveTypes(long organizationId) {
        return jdbc.query("""
                SELECT * FROM hr_leave_type WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new LeaveType(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"), rs.getBigDecimal("annual_allowance"),
                        rs.getBoolean("paid")), organizationId);
    }

    public long createLeaveRequest(long organizationId, long employeeId, long leaveTypeId,
                                   LocalDate startDate, LocalDate endDate, BigDecimal days,
                                   String reason) {
        jdbc.update("""
                INSERT INTO hr_leave_request (organization_id, employee_id, leave_type_id,
                    start_date, end_date, days, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, employeeId, leaveTypeId, Date.valueOf(startDate),
                Date.valueOf(endDate), days, reason);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<LeavePolicy.Window> leaveWindows(long employeeId) {
        return jdbc.query("""
                SELECT start_date, end_date, status FROM hr_leave_request WHERE employee_id = ?
                """, (rs, n) -> new LeavePolicy.Window(rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(), rs.getString("status")), employeeId);
    }

    /** The approved days of one leave type in one calendar year. */
    public BigDecimal usedLeaveDays(long employeeId, long leaveTypeId, int year) {
        BigDecimal used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(days), 0) FROM hr_leave_request
                WHERE employee_id = ? AND leave_type_id = ? AND status = 'APPROVED'
                  AND YEAR(start_date) = ?
                """, BigDecimal.class, employeeId, leaveTypeId, year);
        return used == null ? BigDecimal.ZERO : used;
    }

    public Optional<LeaveRequest> leaveRequest(long organizationId, long requestId) {
        List<LeaveRequest> rows = jdbc.query("""
                SELECT * FROM hr_leave_request WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new LeaveRequest(id(rs.getObject("id")), id(rs.getObject("employee_id")),
                        id(rs.getObject("leave_type_id")), rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(), rs.getBigDecimal("days"),
                        rs.getString("reason"), rs.getString("status"),
                        rs.getString("decided_by")), organizationId, requestId);
        return rows.stream().findFirst();
    }

    public List<LeaveRequest> leaveRequests(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM hr_leave_request WHERE organization_id = ? AND status LIKE ?
                ORDER BY start_date DESC
                """, (rs, n) -> new LeaveRequest(id(rs.getObject("id")), id(rs.getObject("employee_id")),
                        id(rs.getObject("leave_type_id")), rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(), rs.getBigDecimal("days"),
                        rs.getString("reason"), rs.getString("status"),
                        rs.getString("decided_by")), organizationId, filter);
    }

    public void decideLeave(long organizationId, long requestId, String status, String actor) {
        jdbc.update("""
                UPDATE hr_leave_request SET status = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ? AND status = 'SUBMITTED'
                """, status, actor, organizationId, requestId);
    }

    // ------------------------------------------------------------------ timesheets

    public long createTimesheet(long organizationId, long employeeId, LocalDate workDate,
                                String projectCode, BigDecimal hours, boolean billable,
                                String description) {
        jdbc.update("""
                INSERT INTO hr_timesheet (organization_id, employee_id, work_date, project_code,
                    hours, billable, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, employeeId, Date.valueOf(workDate), projectCode, hours,
                billable, description);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Timesheet> timesheets(long organizationId, long employeeId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM hr_timesheet
                WHERE organization_id = ? AND employee_id = ? AND status LIKE ?
                ORDER BY work_date DESC
                """, (rs, n) -> mapTimesheet(rs), organizationId, employeeId, filter);
    }

    public List<Timesheet> timesheets(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM hr_timesheet WHERE organization_id = ? AND status LIKE ?
                ORDER BY work_date DESC
                """, (rs, n) -> mapTimesheet(rs), organizationId, filter);
    }

    private Timesheet mapTimesheet(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Timesheet(id(rs.getObject("id")), id(rs.getObject("employee_id")),
                rs.getDate("work_date").toLocalDate(), rs.getString("project_code"),
                rs.getBigDecimal("hours"), rs.getBoolean("billable"),
                rs.getString("description"), rs.getString("status"));
    }

    public void approveTimesheet(long organizationId, long timesheetId, String actor) {
        jdbc.update("""
                UPDATE hr_timesheet SET status = 'APPROVED', approved_by = ?
                WHERE organization_id = ? AND id = ? AND status = 'SUBMITTED'
                """, actor, organizationId, timesheetId);
    }

    /** One approved timesheet line of a project. */
    public record ProjectHours(long employeeId, BigDecimal hours, boolean billable) {
    }

    /** The approved timesheet hours of one project (feeds the project costing). */
    public List<ProjectHours> approvedProjectHours(long organizationId, String projectCode) {
        return jdbc.query("""
                SELECT employee_id, hours, billable FROM hr_timesheet
                WHERE organization_id = ? AND project_code = ? AND status = 'APPROVED'
                """, (rs, n) -> new ProjectHours(id(rs.getObject("employee_id")),
                        rs.getBigDecimal("hours"), rs.getBoolean("billable")),
                organizationId, projectCode);
    }

    /** The approved hours of one employee in one month (feeds the payroll and the projects). */
    public BigDecimal approvedHours(long employeeId, String period) {
        BigDecimal hours = jdbc.queryForObject("""
                SELECT COALESCE(SUM(hours), 0) FROM hr_timesheet
                WHERE employee_id = ? AND status = 'APPROVED'
                  AND DATE_FORMAT(work_date, '%Y-%m') = ?
                """, BigDecimal.class, employeeId, period);
        return hours == null ? BigDecimal.ZERO : hours;
    }

    // ------------------------------------------------------------------ expenses

    public long createExpenseClaim(long organizationId, long employeeId, String claimNo,
                                   BigDecimal total, String currency) {
        jdbc.update("""
                INSERT INTO hr_expense_claim (organization_id, employee_id, claim_no, total, currency)
                VALUES (?, ?, ?, ?, ?)
                """, organizationId, employeeId, claimNo, total, currency);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void addExpenseLine(long claimId, LocalDate expenseDate, String category,
                               BigDecimal amount, String receiptRef) {
        jdbc.update("""
                INSERT INTO hr_expense_line (claim_id, expense_date, category, amount, receipt_ref)
                VALUES (?, ?, ?, ?, ?)
                """, claimId, Date.valueOf(expenseDate), category, amount, receiptRef);
    }

    public Optional<ExpenseClaim> expenseClaim(long organizationId, long claimId) {
        List<ExpenseClaim> rows = jdbc.query("""
                SELECT * FROM hr_expense_claim WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapClaim(rs), organizationId, claimId);
        return rows.stream().findFirst();
    }

    public List<ExpenseClaim> expenseClaims(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM hr_expense_claim WHERE organization_id = ? AND status LIKE ?
                ORDER BY id DESC
                """, (rs, n) -> mapClaim(rs), organizationId, filter);
    }

    private ExpenseClaim mapClaim(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExpenseClaim(id(rs.getObject("id")), id(rs.getObject("employee_id")),
                rs.getString("claim_no"), rs.getBigDecimal("total"), rs.getString("currency"),
                rs.getString("status"));
    }

    public List<ExpenseLine> expenseLines(long claimId) {
        return jdbc.query("""
                SELECT * FROM hr_expense_line WHERE claim_id = ? ORDER BY expense_date
                """, (rs, n) -> new ExpenseLine(id(rs.getObject("id")), id(rs.getObject("claim_id")),
                        rs.getDate("expense_date").toLocalDate(), rs.getString("category"),
                        rs.getBigDecimal("amount"), rs.getString("receipt_ref")), claimId);
    }

    public void decideExpense(long organizationId, long claimId, String status, String actor) {
        jdbc.update("""
                UPDATE hr_expense_claim SET status = ?, decided_by = ?
                WHERE organization_id = ? AND id = ? AND status = 'SUBMITTED'
                """, status, actor, organizationId, claimId);
    }

    // ------------------------------------------------------------------ incidents

    public long createIncident(long organizationId, long siteId, Long employeeId,
                               LocalDate incidentDate, String category, String severity,
                               String description) {
        jdbc.update("""
                INSERT INTO hr_incident (organization_id, site_id, employee_id, incident_date,
                    category, severity, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, siteId, employeeId, Date.valueOf(incidentDate), category,
                severity, description);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Incident> incidents(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM hr_incident WHERE organization_id = ? AND status LIKE ?
                ORDER BY incident_date DESC
                """, (rs, n) -> new Incident(id(rs.getObject("id")), id(rs.getObject("site_id")),
                        rs.getObject("employee_id") == null ? null : id(rs.getObject("employee_id")),
                        rs.getDate("incident_date").toLocalDate(), rs.getString("category"),
                        rs.getString("severity"), rs.getString("description"),
                        rs.getString("status")), organizationId, filter);
    }

    public void updateIncidentStatus(long organizationId, long incidentId, String status) {
        jdbc.update("""
                UPDATE hr_incident SET status = ? WHERE organization_id = ? AND id = ?
                """, status, organizationId, incidentId);
    }

    // ------------------------------------------------------------------ payroll

    public long createPayrollRun(long organizationId, String period) {
        jdbc.update("""
                INSERT IGNORE INTO hr_payroll_run (organization_id, period) VALUES (?, ?)
                """, organizationId, period);
        Long runId = jdbc.queryForObject("""
                SELECT id FROM hr_payroll_run WHERE organization_id = ? AND period = ?
                """, Long.class, organizationId, period);
        return runId;
    }

    public void addPayrollLine(long runId, long employeeId, BigDecimal base, BigDecimal allowances,
                               BigDecimal deductions, BigDecimal net, String costCentre) {
        jdbc.update("""
                INSERT INTO hr_payroll_line (run_id, employee_id, base_amount, allowances,
                    deductions, net_amount, cost_centre)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE base_amount = VALUES(base_amount),
                    allowances = VALUES(allowances), deductions = VALUES(deductions),
                    net_amount = VALUES(net_amount), cost_centre = VALUES(cost_centre)
                """, runId, employeeId, base, allowances, deductions, net, costCentre);
    }

    public Optional<PayrollRun> payrollRun(long organizationId, String period) {
        List<PayrollRun> rows = jdbc.query("""
                SELECT * FROM hr_payroll_run WHERE organization_id = ? AND period = ?
                """, (rs, n) -> new PayrollRun(id(rs.getObject("id")), rs.getString("period"),
                        rs.getString("status")), organizationId, period);
        return rows.stream().findFirst();
    }

    /** The export rows of one run. */
    public List<java.util.Map<String, Object>> payrollLines(long runId) {
        return jdbc.queryForList("""
                SELECT e.employee_no, e.first_name, e.last_name, l.base_amount, l.allowances,
                       l.deductions, l.net_amount, l.cost_centre
                FROM hr_payroll_line l JOIN hr_employee e ON e.id = l.employee_id
                WHERE l.run_id = ? ORDER BY e.employee_no
                """, runId);
    }

    public void markExported(long organizationId, long runId, String actor) {
        jdbc.update("""
                UPDATE hr_payroll_run SET status = 'EXPORTED', exported_by = ?,
                    exported_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ? AND status = 'OPEN'
                """, actor, organizationId, runId);
    }

    /** The code of a cost centre (for the payroll export). */
    public String costCentreCode(long costCentreId) {
        return jdbc.queryForObject("SELECT code FROM hr_cost_centre WHERE id = ?",
                String.class, costCentreId);
    }

    /** The employees with an active contract (the payroll candidates). */
    public List<Employee> payrollCandidates(long organizationId) {
        return jdbc.query("""
                SELECT DISTINCT e.* FROM hr_employee e
                JOIN hr_contract c ON c.employee_id = e.id AND c.status = 'ACTIVE'
                WHERE e.organization_id = ? AND e.deleted_at IS NULL AND e.status = 'ACTIVE'
                ORDER BY e.employee_no
                """, (rs, n) -> mapEmployee(rs), organizationId);
    }
}
