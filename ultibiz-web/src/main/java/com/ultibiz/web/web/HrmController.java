package com.ultibiz.web.web;

import com.ultibiz.core.application.HrmService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.hrm.ExpensePolicy;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The HRM console: employees, leave, timesheets, expenses, incidents and the payroll. */
@Controller
@RequestMapping("/hr")
public class HrmController {

    private final HrmService hrm;
    private final SiteScopeProvider scopeProvider;

    public HrmController(HrmService hrm, SiteScopeProvider scopeProvider) {
        this.hrm = hrm;
        this.scopeProvider = scopeProvider;
    }

    // ------------------------------------------------------------------ employees

    @GetMapping
    public String employees(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("employees", hrm.employees(scope));
        return "hr/employees";
    }

    @GetMapping("/employees/{id}")
    public String employee(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("employee", hrm.exportPersonalData(scope, id));
        model.addAttribute("employeeId", id);
        return "hr/employee";
    }

    @PostMapping("/employees")
    public String hire(@RequestParam String employeeNo,
                       @RequestParam String firstName,
                       @RequestParam String lastName,
                       @RequestParam(required = false) String personalEmail,
                       @RequestParam(required = false) String phone,
                       @RequestParam(required = false) String nationalId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                           LocalDate birthDate,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                           LocalDate hireDate,
                       @AuthenticationPrincipal UltiBizUserPrincipal principal,
                       RedirectAttributes redirect) {
        try {
            hrm.hire(scopeProvider.current(), actor(principal), employeeNo, firstName, lastName,
                    hireDate, personalEmail, phone, nationalId, birthDate);
            redirect.addFlashAttribute("flash", "hr.employeeHired");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr";
    }

    // ------------------------------------------------------------------ leave

    @GetMapping("/leave")
    public String leave(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("requests", hrm.leaveRequests(scope, status));
        model.addAttribute("employees", hrm.employees(scope));
        model.addAttribute("leaveTypes", hrm.leaveTypes(scope));
        model.addAttribute("status", status);
        return "hr/leave";
    }

    @PostMapping("/leave")
    public String requestLeave(@RequestParam long employeeId,
                               @RequestParam String leaveType,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate startDate,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate endDate,
                               @RequestParam(required = false) String reason,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        try {
            hrm.requestLeave(scopeProvider.current(), actor(principal), employeeId, leaveType,
                    startDate, endDate, reason);
            redirect.addFlashAttribute("flash", "hr.leaveSubmitted");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/leave";
    }

    @PostMapping("/leave/{id}/decide")
    public String decideLeave(@PathVariable long id,
                              @RequestParam boolean approve,
                              @AuthenticationPrincipal UltiBizUserPrincipal principal,
                              RedirectAttributes redirect) {
        try {
            hrm.decideLeave(scopeProvider.current(), actor(principal), id, approve);
            redirect.addFlashAttribute("flash", approve ? "hr.leaveApproved" : "hr.leaveRejected");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/leave";
    }

    // ------------------------------------------------------------------ timesheets

    @GetMapping("/timesheets")
    public String timesheets(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("timesheets", hrm.timesheets(scope, status));
        model.addAttribute("employees", hrm.employees(scope));
        model.addAttribute("status", status);
        return "hr/timesheets";
    }

    @PostMapping("/timesheets")
    public String bookTimesheet(@RequestParam long employeeId,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate workDate,
                                @RequestParam(required = false) String projectCode,
                                @RequestParam BigDecimal hours,
                                @RequestParam(defaultValue = "false") boolean billable,
                                @RequestParam(required = false) String description,
                                @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                RedirectAttributes redirect) {
        try {
            hrm.bookTimesheet(scopeProvider.current(), actor(principal), employeeId, workDate,
                    projectCode, hours, billable, description);
            redirect.addFlashAttribute("flash", "hr.timesheetBooked");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/timesheets";
    }

    @PostMapping("/timesheets/{id}/approve")
    public String approveTimesheet(@PathVariable long id,
                                   @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                   RedirectAttributes redirect) {
        try {
            hrm.approveTimesheet(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "hr.timesheetApproved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/timesheets";
    }

    // ------------------------------------------------------------------ expenses

    @GetMapping("/expenses")
    public String expenses(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("claims", hrm.expenseClaims(scope, status));
        model.addAttribute("employees", hrm.employees(scope));
        model.addAttribute("status", status);
        return "hr/expenses";
    }

    @PostMapping("/expenses")
    public String submitExpense(@RequestParam long employeeId,
                                @RequestParam String claimNo,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                    LocalDate expenseDate,
                                @RequestParam String category,
                                @RequestParam BigDecimal amount,
                                @RequestParam(required = false) String receiptRef,
                                @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                RedirectAttributes redirect) {
        try {
            hrm.submitExpenseClaim(scopeProvider.current(), actor(principal), employeeId, claimNo,
                    "IDR", List.of(new ExpensePolicy.Line(amount, category)),
                    List.of(expenseDate), List.of(receiptRef));
            redirect.addFlashAttribute("flash", "hr.expenseSubmitted");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/expenses";
    }

    @PostMapping("/expenses/{id}/decide")
    public String decideExpense(@PathVariable long id,
                                @RequestParam boolean approve,
                                @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                RedirectAttributes redirect) {
        try {
            hrm.decideExpense(scopeProvider.current(), actor(principal), id, approve);
            redirect.addFlashAttribute("flash", approve ? "hr.expenseApproved" : "hr.expenseRejected");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/expenses";
    }

    // ------------------------------------------------------------------ incidents

    @GetMapping("/incidents")
    public String incidents(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("incidents", hrm.incidents(scope, status));
        model.addAttribute("status", status);
        return "hr/incidents";
    }

    @PostMapping("/incidents")
    public String registerIncident(@RequestParam(required = false) Long employeeId,
                                   @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       LocalDate incidentDate,
                                   @RequestParam String category,
                                   @RequestParam String severity,
                                   @RequestParam String description,
                                   @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                   RedirectAttributes redirect) {
        try {
            hrm.registerIncident(scopeProvider.current(), actor(principal), employeeId,
                    incidentDate, category, severity, description);
            redirect.addFlashAttribute("flash", "hr.incidentRegistered");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/incidents";
    }

    @PostMapping("/incidents/{id}/status")
    public String updateIncident(@PathVariable long id,
                                 @RequestParam String status,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                 RedirectAttributes redirect) {
        try {
            hrm.updateIncidentStatus(scopeProvider.current(), actor(principal), id, status);
            redirect.addFlashAttribute("flash", "hr.incidentUpdated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/incidents";
    }

    // ------------------------------------------------------------------ payroll

    @GetMapping("/payroll")
    public String payroll(@RequestParam(required = false) String period, Model model) {
        SiteScope scope = scopeProvider.current();
        String current = period == null || period.isBlank() ? HrmService.currentPeriod() : period;
        model.addAttribute("period", current);
        model.addAttribute("run", hrmPayrollRun(scope, current));
        return "hr/payroll";
    }

    private Object hrmPayrollRun(SiteScope scope, String period) {
        return hrm.payrollRun(scope, period)
                .map(run -> new PayrollView(run.id(), run.period(), run.status(),
                        hrm.payrollLines(run.id())))
                .orElse(null);
    }

    /** The payroll view of one run. */
    public record PayrollView(long id, String period, String status,
                              List<java.util.Map<String, Object>> lines) {
    }

    @PostMapping("/payroll/run")
    public String runPayroll(@RequestParam String period,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            hrm.runPayroll(scopeProvider.current(), actor(principal), period);
            redirect.addFlashAttribute("flash", "hr.payrollRunDone");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/hr/payroll?period=" + period;
    }

    @GetMapping(value = "/payroll/export", produces = "text/csv")
    @org.springframework.web.bind.annotation.ResponseBody
    public String exportPayroll(@RequestParam String period,
                                @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        return hrm.exportPayroll(scopeProvider.current(), actor(principal), period);
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
