package com.ultibiz.web.web;

import com.ultibiz.core.application.HrmService;
import com.ultibiz.core.application.ProjectService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** The Projects console: projects, WBS, milestones, assignments and the cost report. */
@Controller
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projects;
    private final HrmService hrm;
    private final SiteScopeProvider scopeProvider;

    public ProjectController(ProjectService projects, HrmService hrm,
                             SiteScopeProvider scopeProvider) {
        this.projects = projects;
        this.hrm = hrm;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("projects", projects.projects(scope, status));
        model.addAttribute("status", status);
        return "projects/list";
    }

    @PostMapping
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate startDate,
                         @RequestParam(required = false) BigDecimal budgetAmount,
                         @RequestParam(required = false) BigDecimal billingRate,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            long id = projects.createProject(scopeProvider.current(), actor(principal), code,
                    name, null, startDate, null, budgetAmount, "IDR", billingRate);
            redirect.addFlashAttribute("flash", "prj.created");
            return "redirect:/projects/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/projects";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("project", projects.project(scope, id));
        model.addAttribute("wbs", projects.wbs(id));
        model.addAttribute("milestones", projects.milestones(id));
        model.addAttribute("assignments", projects.assignments(id));
        model.addAttribute("report", projects.costReport(scope, id));
        model.addAttribute("employees", hrm.employees(scope));
        return "projects/detail";
    }

    @PostMapping("/{id}/status")
    public String status(@PathVariable long id,
                         @RequestParam String status,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            projects.updateStatus(scopeProvider.current(), actor(principal), id, status);
            redirect.addFlashAttribute("flash", "prj.statusUpdated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/wbs")
    public String addWbs(@PathVariable long id,
                         @RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) BigDecimal budgetHours,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            projects.addWbs(scopeProvider.current(), actor(principal), id, code, name, null,
                    budgetHours);
            redirect.addFlashAttribute("flash", "prj.wbsAdded");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/{id}/milestones")
    public String addMilestone(@PathVariable long id,
                               @RequestParam String code,
                               @RequestParam String name,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate dueDate,
                               @RequestParam(defaultValue = "0") BigDecimal amount,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        try {
            projects.addMilestone(scopeProvider.current(), actor(principal), id, code, name,
                    dueDate, amount);
            redirect.addFlashAttribute("flash", "prj.milestoneAdded");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/milestones/{id}/reach")
    public String reachMilestone(@PathVariable long id,
                                 @RequestParam long projectId,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                 RedirectAttributes redirect) {
        try {
            projects.reachMilestone(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "prj.milestoneReached");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + projectId;
    }

    @PostMapping("/milestones/{id}/invoice")
    public String invoiceMilestone(@PathVariable long id,
                                   @RequestParam long projectId,
                                   @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                   RedirectAttributes redirect) {
        try {
            projects.invoiceMilestone(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "prj.milestoneInvoiced");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + projectId;
    }

    @PostMapping("/{id}/assignments")
    public String assign(@PathVariable long id,
                         @RequestParam long employeeId,
                         @RequestParam(required = false) String role,
                         @RequestParam BigDecimal hoursPerWeek,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate startDate,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            projects.assign(scopeProvider.current(), actor(principal), id, employeeId, role,
                    hoursPerWeek, startDate, null);
            redirect.addFlashAttribute("flash", "prj.assigned");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
