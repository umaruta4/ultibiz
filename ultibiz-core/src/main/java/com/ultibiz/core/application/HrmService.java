package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.hrm.ExpensePolicy;
import com.ultibiz.core.hrm.HrmException;
import com.ultibiz.core.hrm.LeavePolicy;
import com.ultibiz.core.hrm.PayrollCalculator;
import com.ultibiz.core.hrm.TimesheetPolicy;
import com.ultibiz.core.infrastructure.HrmRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The HRM plugin: employees, contracts, leave, timesheets, expenses, incidents
 * and the payroll export, plus the GDPR export/erasure hooks.
 */
@Service
public class HrmService {

    private final HrmRepository hr;
    private final AuditService audit;

    public HrmService(HrmRepository hr, AuditService audit) {
        this.hr = hr;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ employees

    /** Hires an employee. The personal data is collected knowingly (GDPR scope). */
    @Transactional
    public long hire(SiteScope scope, String actor, String employeeNo, String firstName,
                     String lastName, LocalDate hireDate, String personalEmail, String phone,
                     String nationalId, LocalDate birthDate) {
        if (employeeNo == null || employeeNo.isBlank() || firstName == null || firstName.isBlank()
                || lastName == null || lastName.isBlank()) {
            throw new HrmException("An employee needs a number, a first name and a last name");
        }
        long id = hr.createEmployee(scope.organizationId(), site(scope), employeeNo, null,
                firstName.trim(), lastName.trim(), personalEmail, phone, nationalId, birthDate,
                hireDate == null ? LocalDate.now() : hireDate, null, null);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_EMPLOYEE_HIRE",
                "hr_employee", String.valueOf(id), "{\"employeeNo\":\"" + employeeNo + "\"}", null));
        return id;
    }

    // ------------------------------------------------------------------ leave

    /** Submits a leave request: working days, overlap and balance are enforced. */
    @Transactional
    public long requestLeave(SiteScope scope, String actor, long employeeId, String leaveTypeCode,
                             LocalDate startDate, LocalDate endDate, String reason) {
        BigDecimal days = LeavePolicy.workingDays(startDate, endDate);
        if (LeavePolicy.overlaps(startDate, endDate, hr.leaveWindows(employeeId))) {
            throw new HrmException("The leave overlaps an existing request");
        }
        HrmRepository.LeaveType type = hr.leaveTypes(scope.organizationId()).stream()
                .filter(t -> t.code().equals(leaveTypeCode)).findFirst()
                .orElseThrow(() -> new HrmException("Unknown leave type: " + leaveTypeCode));
        BigDecimal used = hr.usedLeaveDays(employeeId, type.id(), startDate.getYear());
        LeavePolicy.checkBalance(type.annualAllowance(), used, days);
        long id = hr.createLeaveRequest(scope.organizationId(), employeeId, type.id(),
                startDate, endDate, days, reason);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_LEAVE_REQUEST",
                "hr_leave_request", String.valueOf(id),
                "{\"days\":" + days + ",\"type\":\"" + leaveTypeCode + "\"}", null));
        return id;
    }

    /** Approves or rejects a submitted request. */
    @Transactional
    public void decideLeave(SiteScope scope, String actor, long requestId, boolean approve) {
        HrmRepository.LeaveRequest request = hr.leaveRequest(scope.organizationId(), requestId)
                .orElseThrow(() -> new HrmException("Unknown leave request: " + requestId));
        if (!"SUBMITTED".equals(request.status())) {
            throw new HrmException("The leave request is " + request.status() + ", not SUBMITTED");
        }
        hr.decideLeave(scope.organizationId(), requestId, approve ? "APPROVED" : "REJECTED", actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor,
                approve ? "HR_LEAVE_APPROVE" : "HR_LEAVE_REJECT",
                "hr_leave_request", String.valueOf(requestId), null, null));
    }

    // ------------------------------------------------------------------ timesheets

    /** Books a timesheet entry (feeds the project costs in P8/Projects). */
    @Transactional
    public long bookTimesheet(SiteScope scope, String actor, long employeeId, LocalDate workDate,
                              String projectCode, BigDecimal hours, boolean billable,
                              String description) {
        TimesheetPolicy.check(workDate, hours);
        long id;
        try {
            id = hr.createTimesheet(scope.organizationId(), employeeId, workDate,
                    projectCode == null || projectCode.isBlank() ? null : projectCode.trim(),
                    hours, billable, description);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new HrmException("A timesheet for this day and project already exists");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_TIMESHEET_BOOK",
                "hr_timesheet", String.valueOf(id), "{\"hours\":" + hours + "}", null));
        return id;
    }

    @Transactional
    public void approveTimesheet(SiteScope scope, String actor, long timesheetId) {
        hr.approveTimesheet(scope.organizationId(), timesheetId, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_TIMESHEET_APPROVE",
                "hr_timesheet", String.valueOf(timesheetId), null, null));
    }

    // ------------------------------------------------------------------ expenses

    /** Submits an expense claim; the total is computed from the lines, never taken over. */
    @Transactional
    public long submitExpenseClaim(SiteScope scope, String actor, long employeeId, String claimNo,
                                   String currency, List<ExpensePolicy.Line> lines,
                                   List<LocalDate> dates, List<String> receipts) {
        BigDecimal total = ExpensePolicy.total(lines);
        long id = hr.createExpenseClaim(scope.organizationId(), employeeId, claimNo, total,
                currency == null || currency.isBlank() ? "IDR" : currency);
        for (int i = 0; i < lines.size(); i++) {
            ExpensePolicy.Line line = lines.get(i);
            hr.addExpenseLine(id, dates.get(i), line.category(), line.amount(),
                    receipts == null || receipts.size() <= i ? null : receipts.get(i));
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_EXPENSE_SUBMIT",
                "hr_expense_claim", String.valueOf(id), "{\"total\":" + total + "}", null));
        return id;
    }

    @Transactional
    public void decideExpense(SiteScope scope, String actor, long claimId, boolean approve) {
        HrmRepository.ExpenseClaim claim = hr.expenseClaim(scope.organizationId(), claimId)
                .orElseThrow(() -> new HrmException("Unknown expense claim: " + claimId));
        if (!"SUBMITTED".equals(claim.status())) {
            throw new HrmException("The expense claim is " + claim.status() + ", not SUBMITTED");
        }
        hr.decideExpense(scope.organizationId(), claimId, approve ? "APPROVED" : "REJECTED", actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor,
                approve ? "HR_EXPENSE_APPROVE" : "HR_EXPENSE_REJECT",
                "hr_expense_claim", String.valueOf(claimId), null, null));
    }

    // ------------------------------------------------------------------ incidents

    @Transactional
    public long registerIncident(SiteScope scope, String actor, Long employeeId,
                                 LocalDate incidentDate, String category, String severity,
                                 String description) {
        if (description == null || description.isBlank()) {
            throw new HrmException("An incident needs a description");
        }
        long id = hr.createIncident(scope.organizationId(), site(scope), employeeId,
                incidentDate == null ? LocalDate.now() : incidentDate, category, severity,
                description);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_INCIDENT_REGISTER",
                "hr_incident", String.valueOf(id), "{\"severity\":\"" + severity + "\"}", null));
        return id;
    }

    @Transactional
    public void updateIncidentStatus(SiteScope scope, String actor, long incidentId, String status) {
        hr.updateIncidentStatus(scope.organizationId(), incidentId, status);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_INCIDENT_STATUS",
                "hr_incident", String.valueOf(incidentId), "{\"status\":\"" + status + "\"}", null));
    }

    // ------------------------------------------------------------------ payroll

    /**
     * Runs the payroll of one period: every employee with an active contract
     * gets a line (net = base + allowances − deductions, allocated to the cost
     * centre). Returns the run id.
     */
    @Transactional
    public long runPayroll(SiteScope scope, String actor, String period) {
        if (hr.payrollRun(scope.organizationId(), period)
                .filter(r -> "EXPORTED".equals(r.status())).isPresent()) {
            throw new HrmException("The payroll of " + period + " is already exported");
        }
        long runId = hr.createPayrollRun(scope.organizationId(), period);
        for (HrmRepository.Employee employee : hr.payrollCandidates(scope.organizationId())) {
            HrmRepository.Contract contract = hr.activeContract(employee.id())
                    .orElseThrow(() -> new HrmException(
                            "No active contract for " + employee.employeeNo()));
            BigDecimal net = PayrollCalculator.net(contract.baseSalary(), BigDecimal.ZERO,
                    BigDecimal.ZERO);
            hr.addPayrollLine(runId, employee.id(), contract.baseSalary(), BigDecimal.ZERO,
                    BigDecimal.ZERO, net, costCentreCode(employee));
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_PAYROLL_RUN",
                "hr_payroll_run", String.valueOf(runId), "{\"period\":\"" + period + "\"}", null));
        return runId;
    }

    /** The payroll export as CSV (employee, base, allowances, deductions, net, cost centre). */
    @Transactional
    public String exportPayroll(SiteScope scope, String actor, String period) {
        HrmRepository.PayrollRun run = hr.payrollRun(scope.organizationId(), period)
                .orElseThrow(() -> new HrmException("No payroll run for " + period));
        StringBuilder csv = new StringBuilder(
                "employee_no;first_name;last_name;base;allowances;deductions;net;cost_centre\n");
        for (Map<String, Object> line : hr.payrollLines(run.id())) {
            csv.append(line.get("employee_no")).append(';')
                    .append(line.get("first_name")).append(';')
                    .append(line.get("last_name")).append(';')
                    .append(line.get("base_amount")).append(';')
                    .append(line.get("allowances")).append(';')
                    .append(line.get("deductions")).append(';')
                    .append(line.get("net_amount")).append(';')
                    .append(line.get("cost_centre") == null ? "" : line.get("cost_centre"))
                    .append('\n');
        }
        hr.markExported(scope.organizationId(), run.id(), actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_PAYROLL_EXPORT",
                "hr_payroll_run", String.valueOf(run.id()), "{\"period\":\"" + period + "\"}", null));
        return csv.toString();
    }

    // ------------------------------------------------------------------ GDPR

    /** The personal data export of one employee (GDPR art. 20 style). */
    public Map<String, Object> exportPersonalData(SiteScope scope, long employeeId) {
        return hr.personalData(scope.organizationId(), employeeId);
    }

    /**
     * The erasure hook: the personal fields are anonymised, the financial rows
     * (contracts, payroll, expenses) stay — the books must not change. The
     * request itself is logged.
     */
    @Transactional
    public void erasePersonalData(SiteScope scope, String actor, long employeeId) {
        hr.employee(scope.organizationId(), employeeId)
                .orElseThrow(() -> new HrmException("Unknown employee: " + employeeId));
        hr.anonymise(scope.organizationId(), employeeId);
        hr.logErasure(scope.organizationId(), employeeId, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HR_GDPR_ERASURE",
                "hr_employee", String.valueOf(employeeId), null, null));
    }

    // ------------------------------------------------------------------ helpers

    private String costCentreCode(HrmRepository.Employee employee) {
        return employee.costCentreId() == null ? null : hr.costCentreCode(employee.costCentreId());
    }

    /** The site the HRM rows are written under. */
    private static long site(SiteScope scope) {
        return scope.siteIds().stream().findFirst().orElse(0L);
    }

    /** The current pay period (YYYY-MM). */
    public static String currentPeriod() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** The employees of the organisation (for the UI). */
    public List<HrmRepository.Employee> employees(SiteScope scope) {
        return hr.employees(scope.organizationId());
    }

    public List<HrmRepository.LeaveType> leaveTypes(SiteScope scope) {
        return hr.leaveTypes(scope.organizationId());
    }

    public List<HrmRepository.LeaveRequest> leaveRequests(SiteScope scope, String status) {
        return hr.leaveRequests(scope.organizationId(), status);
    }

    public List<HrmRepository.Timesheet> timesheets(SiteScope scope, String status) {
        return hr.timesheets(scope.organizationId(), status);
    }

    public List<HrmRepository.ExpenseClaim> expenseClaims(SiteScope scope, String status) {
        return hr.expenseClaims(scope.organizationId(), status);
    }

    public List<HrmRepository.ExpenseLine> expenseLines(long claimId) {
        return hr.expenseLines(claimId);
    }

    public List<HrmRepository.Incident> incidents(SiteScope scope, String status) {
        return hr.incidents(scope.organizationId(), status);
    }

    public List<Map<String, Object>> payrollLines(long runId) {
        return new ArrayList<>(hr.payrollLines(runId));
    }

    /** The payroll run of one period, when it exists. */
    public java.util.Optional<HrmRepository.PayrollRun> payrollRun(SiteScope scope, String period) {
        return hr.payrollRun(scope.organizationId(), period);
    }
}
