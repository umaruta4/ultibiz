package com.ultibiz.web.web;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.OrganizationService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteService;
import com.ultibiz.core.application.UserService;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** View row for the recent-activity table (time already formatted). */
    public record AuditRow(String time, String actor, String action) {
    }

    private final OrganizationService organizations;
    private final SiteService sites;
    private final UserService users;
    private final AuditService audit;
    private final SiteScopeProvider scopeProvider;
    private final String appVersion;

    public HomeController(OrganizationService organizations, SiteService sites,
                          UserService users, AuditService audit,
                          SiteScopeProvider scopeProvider,
                          @Value("${info.app.version:dev}") String appVersion) {
        this.organizations = organizations;
        this.sites = sites;
        this.users = users;
        this.audit = audit;
        this.scopeProvider = scopeProvider;
        this.appVersion = appVersion;
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        SiteScope scope = scopeProvider.current();

        model.addAttribute("orgCount", organizations.count());
        model.addAttribute("siteCount", sites.countVisibleTo(scope));
        model.addAttribute("userCount", users.count());
        model.addAttribute("uptime", formatUptime());
        model.addAttribute("username", principal != null ? principal.displayName() : "");
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("serverTime",
                ZonedDateTime.now(APP_ZONE).format(TS) + " WIB");

        List<AuditRow> rows = principal != null
                ? audit.recent(principal.organizationId(), 10).stream()
                        .map(e -> new AuditRow(e.createdAt().atZone(APP_ZONE).format(TS),
                                e.actor(), e.action()))
                        .toList()
                : List.of();
        model.addAttribute("auditRows", rows);
        return "home";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    private static String formatUptime() {
        Duration up = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        long hours = up.toHours();
        long minutes = up.toMinutesPart();
        return hours + "h " + minutes + "m";
    }
}
