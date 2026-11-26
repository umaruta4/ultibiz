package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.HrmRepository;
import com.ultibiz.core.infrastructure.ProjectRepository;
import com.ultibiz.core.projects.MilestonePolicy;
import com.ultibiz.core.projects.ProjectCosting;
import com.ultibiz.core.projects.ProjectException;
import com.ultibiz.core.projects.ResourcePolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Projects plugin: projects with a WBS, budgets, billing milestones and
 * resource assignments; the cost comes from the approved timesheets, the
 * revenue from billable hours and invoiced milestones — and the invoicing
 * posts a real journal, so the profitability feeds the accounting.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final HrmRepository hr;
    private final AccountRepository accounts;
    private final JournalService journals;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, HrmRepository hr,
                          AccountRepository accounts, JournalService journals,
                          AuditService audit) {
        this.projects = projects;
        this.hr = hr;
        this.accounts = accounts;
        this.journals = journals;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ projects

    @Transactional
    public long createProject(SiteScope scope, String actor, String code, String name,
                              Long customerId, LocalDate startDate, LocalDate endDate,
                              BigDecimal budgetAmount, String currency, BigDecimal billingRate) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new ProjectException("A project needs a code and a name");
        }
        long id = projects.createProject(scope.organizationId(), site(scope), code.trim(),
                name.trim(), customerId, actor,
                startDate == null ? LocalDate.now() : startDate, endDate,
                budgetAmount == null ? BigDecimal.ZERO : budgetAmount,
                currency == null || currency.isBlank() ? "IDR" : currency,
                billingRate == null ? BigDecimal.ZERO : billingRate);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_PROJECT_CREATE",
                "prj_project", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    @Transactional
    public long addWbs(SiteScope scope, String actor, long projectId, String code, String name,
                       Long parentId, BigDecimal budgetHours) {
        project(scope, projectId);
        long id = projects.addWbs(scope.organizationId(), projectId, code, name, parentId,
                budgetHours == null ? BigDecimal.ZERO : budgetHours);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_WBS_ADD",
                "prj_wbs", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    @Transactional
    public void updateStatus(SiteScope scope, String actor, long projectId, String status) {
        project(scope, projectId);
        projects.updateProjectStatus(scope.organizationId(), projectId, status);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_PROJECT_STATUS",
                "prj_project", String.valueOf(projectId), "{\"status\":\"" + status + "\"}", null));
    }

    // ------------------------------------------------------------------ milestones

    @Transactional
    public long addMilestone(SiteScope scope, String actor, long projectId, String code,
                             String name, LocalDate dueDate, BigDecimal amount) {
        project(scope, projectId);
        MilestonePolicy.check(name, dueDate, amount);
        long id = projects.addMilestone(scope.organizationId(), projectId, code, name, dueDate,
                amount);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_MILESTONE_ADD",
                "prj_milestone", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    @Transactional
    public void reachMilestone(SiteScope scope, String actor, long milestoneId) {
        ProjectRepository.Milestone milestone = milestone(scope, milestoneId);
        MilestonePolicy.checkTransition(milestone.status(), "REACHED");
        projects.reachMilestone(scope.organizationId(), milestoneId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_MILESTONE_REACH",
                "prj_milestone", String.valueOf(milestoneId), null, null));
    }

    /**
     * Invoices a reached billing milestone: a real journal Dr Accounts
     * Receivable / Cr Sales Revenue is posted and the milestone carries it.
     */
    @Transactional
    public long invoiceMilestone(SiteScope scope, String actor, long milestoneId) {
        ProjectRepository.Milestone milestone = milestone(scope, milestoneId);
        MilestonePolicy.checkTransition(milestone.status(), "INVOICED");
        if (milestone.amount().signum() <= 0) {
            throw new ProjectException("The milestone " + milestone.code()
                    + " is not a billing milestone (amount 0)");
        }
        ProjectRepository.Project project = project(scope, milestone.projectId());

        long arAccount = account(scope.organizationId(), "1200");   // Accounts Receivable
        long revenueAccount = account(scope.organizationId(), "4100"); // Sales Revenue
        JournalDraft draft = new JournalDraft("ID", project.siteId(), "SALES", LocalDate.now(),
                project.currency(), BigDecimal.ONE, milestone.code(), "prj_milestone",
                milestoneId, null,
                "Milestone " + milestone.name() + " of " + project.code(),
                List.of(new JournalLineDraft(arAccount, project.customerId(), project.siteId(),
                                "Milestone " + milestone.name(), milestone.amount(), null, null,
                                null),
                        new JournalLineDraft(revenueAccount, null, project.siteId(),
                                "Milestone " + milestone.name(), null, milestone.amount(), null,
                                null)));
        var journal = journals.post(scope, actor, draft);
        projects.invoiceMilestone(scope.organizationId(), milestoneId, journal.id());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_MILESTONE_INVOICE",
                "prj_milestone", String.valueOf(milestoneId),
                "{\"journal\":\"" + journal.journalNumber() + "\"}", null));
        return journal.id();
    }

    // ------------------------------------------------------------------ assignments

    /** Assigns an employee; the weekly capacity across overlapping assignments is enforced. */
    @Transactional
    public long assign(SiteScope scope, String actor, long projectId, long employeeId,
                       String role, BigDecimal hoursPerWeek, LocalDate startDate,
                       LocalDate endDate) {
        project(scope, projectId);
        HrmRepository.Contract contract = hr.activeContract(employeeId)
                .orElseThrow(() -> new ProjectException(
                        "The employee has no active contract — the capacity is unknown"));
        List<ResourcePolicy.Assignment> existing = projects.assignmentsOfEmployee(employeeId)
                .stream()
                .map(a -> new ResourcePolicy.Assignment(a.projectId(), a.hoursPerWeek(),
                        a.startDate(), a.endDate()))
                .toList();
        LocalDate start = startDate == null ? LocalDate.now() : startDate;
        ResourcePolicy.check(contract.weeklyHours(), existing, projectId, hoursPerWeek, start,
                endDate);
        long id = projects.assign(scope.organizationId(), projectId, employeeId,
                role == null || role.isBlank() ? "Member" : role, hoursPerWeek, start, endDate);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRJ_ASSIGN",
                "prj_assignment", String.valueOf(id),
                "{\"employeeId\":" + employeeId + ",\"hours\":" + hoursPerWeek + "}", null));
        return id;
    }

    // ------------------------------------------------------------------ costing

    /** The cost report of one project. */
    public record CostReport(String projectCode, BigDecimal hoursTotal, BigDecimal hoursBillable,
                             BigDecimal labourCost, BigDecimal hoursRevenue,
                             BigDecimal milestoneRevenue, BigDecimal revenue,
                             BigDecimal profitability, BigDecimal budgetAmount,
                             BigDecimal budgetUsagePercent, Map<Long, BigDecimal> hoursByEmployee) {
    }

    /**
     * The cost and revenue of a project: the approved timesheet hours at the
     * contractual hourly cost, the billable hours at the billing rate and the
     * invoiced milestones.
     */
    public CostReport costReport(SiteScope scope, long projectId) {
        ProjectRepository.Project project = project(scope, projectId);
        List<HrmRepository.ProjectHours> lines = hr.approvedProjectHours(
                scope.organizationId(), project.code());

        BigDecimal hoursTotal = BigDecimal.ZERO;
        BigDecimal hoursBillable = BigDecimal.ZERO;
        BigDecimal labourCost = BigDecimal.ZERO;
        Map<Long, BigDecimal> byEmployee = new LinkedHashMap<>();
        Map<Long, BigDecimal> hourlyCost = new LinkedHashMap<>();
        for (HrmRepository.ProjectHours line : lines) {
            hoursTotal = hoursTotal.add(line.hours());
            if (line.billable()) {
                hoursBillable = hoursBillable.add(line.hours());
            }
            byEmployee.merge(line.employeeId(), line.hours(), BigDecimal::add);
            BigDecimal rate = hourlyCost.computeIfAbsent(line.employeeId(), employeeId -> {
                HrmRepository.Contract contract = hr.activeContract(employeeId)
                        .orElseThrow(() -> new ProjectException(
                                "Employee " + employeeId + " has no active contract"));
                return ProjectCosting.hourlyCost(contract.baseSalary(), contract.weeklyHours());
            });
            labourCost = labourCost.add(ProjectCosting.labourCost(line.hours(), rate));
        }
        BigDecimal milestoneRevenue = projects.milestones(projectId).stream()
                .filter(m -> "INVOICED".equals(m.status()))
                .map(ProjectRepository.Milestone::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hoursRevenue = ProjectCosting.revenue(hoursBillable, project.billingRate(),
                BigDecimal.ZERO);
        BigDecimal revenue = hoursRevenue.add(milestoneRevenue);
        return new CostReport(project.code(), hoursTotal, hoursBillable, labourCost, hoursRevenue,
                milestoneRevenue, revenue, ProjectCosting.profitability(revenue, labourCost),
                project.budgetAmount(),
                ProjectCosting.budgetUsage(labourCost, project.budgetAmount()), byEmployee);
    }

    // ------------------------------------------------------------------ reads

    public List<ProjectRepository.Project> projects(SiteScope scope, String status) {
        return projects.projects(scope.organizationId(), status);
    }

    public ProjectRepository.Project project(SiteScope scope, long projectId) {
        return projects.project(scope.organizationId(), projectId)
                .orElseThrow(() -> new ProjectException("Unknown project: " + projectId));
    }

    public List<ProjectRepository.Wbs> wbs(long projectId) {
        return projects.wbs(projectId);
    }

    public List<ProjectRepository.Milestone> milestones(long projectId) {
        return projects.milestones(projectId);
    }

    public List<ProjectRepository.Assignment> assignments(long projectId) {
        return projects.assignments(projectId);
    }

    private ProjectRepository.Milestone milestone(SiteScope scope, long milestoneId) {
        return projects.milestone(scope.organizationId(), milestoneId)
                .orElseThrow(() -> new ProjectException("Unknown milestone: " + milestoneId));
    }

    private long account(long organizationId, String code) {
        var account = accounts.findByCode(organizationId, code);
        if (account == null) {
            throw new ProjectException("The account " + code + " is missing in the chart");
        }
        return account.id();
    }

    private static long site(SiteScope scope) {
        return scope.siteIds().stream().findFirst().orElse(0L);
    }
}
