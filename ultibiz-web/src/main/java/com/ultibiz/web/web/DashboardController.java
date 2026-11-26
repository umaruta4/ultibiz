package com.ultibiz.web.web;

import com.ultibiz.core.application.DashboardService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The dashboard: the KPI tiles of the role, the threshold alerts and the insight board. */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final SiteScopeProvider scopeProvider;

    public DashboardController(DashboardService dashboard, SiteScopeProvider scopeProvider) {
        this.dashboard = dashboard;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String index(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("tiles", dashboard.tiles(scope, permissions(principal)));
        model.addAttribute("alerts", dashboard.alerts(scope));
        model.addAttribute("insights", dashboard.insightBoard(scope));
        return "dashboard/index";
    }

    @GetMapping("/views")
    public String views(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("views", dashboard.savedViews(scope, userId(principal)));
        return "dashboard/views";
    }

    @PostMapping("/views")
    public String saveView(@RequestParam String code,
                           @RequestParam String title,
                           @RequestParam String module,
                           @RequestParam(required = false) String filters,
                           @RequestParam(defaultValue = "false") boolean shared,
                           @AuthenticationPrincipal UltiBizUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            dashboard.saveView(scopeProvider.current(), actor(principal), userId(principal), code,
                    title, module, filters, shared);
            redirect.addFlashAttribute("flash", "dash.viewSaved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard/views";
    }

    @PostMapping("/views/{id}/delete")
    public String deleteView(@PathVariable long id,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            dashboard.deleteView(scopeProvider.current(), actor(principal), userId(principal), id);
            redirect.addFlashAttribute("flash", "dash.viewDeleted");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard/views";
    }

    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("subscriptions", dashboard.subscriptions(scope, userId(principal)));
        model.addAttribute("files", dashboard.reportFiles(scope, userId(principal)));
        model.addAttribute("catalogue", dashboard.reportCatalogue());
        return "dashboard/reports";
    }

    @PostMapping("/reports")
    public String subscribe(@RequestParam String reportCode,
                            @RequestParam(defaultValue = "MONTHLY") String frequency,
                            @RequestParam(defaultValue = "CSV") String format,
                            @RequestParam(required = false) String email,
                            @AuthenticationPrincipal UltiBizUserPrincipal principal,
                            RedirectAttributes redirect) {
        try {
            String title = dashboard.reportCatalogue().stream()
                    .filter(entry -> entry[0].equals(reportCode))
                    .map(entry -> entry[1]).findFirst().orElse(reportCode);
            dashboard.subscribe(scopeProvider.current(), actor(principal), userId(principal),
                    reportCode, title, frequency, format, email);
            redirect.addFlashAttribute("flash", "dash.subscribed");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard/reports";
    }

    @PostMapping("/reports/{id}/run")
    public String runNow(@PathVariable long id,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            var subscription = dashboard.subscriptions(scopeProvider.current(), userId(principal))
                    .stream().filter(s -> s.id() == id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown subscription"));
            dashboard.generate(subscription);
            redirect.addFlashAttribute("flash", "dash.reportGenerated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard/reports";
    }

    /** The permission codes of the current user. */
    private static Set<String> permissions(UltiBizUserPrincipal principal) {
        Set<String> codes = new LinkedHashSet<>();
        if (principal != null) {
            principal.getAuthorities().forEach(authority -> codes.add(authority.getAuthority()));
        }
        return codes;
    }

    private static long userId(UltiBizUserPrincipal principal) {
        return principal == null ? 0L : principal.userId();
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
