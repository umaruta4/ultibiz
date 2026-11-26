package com.ultibiz.web.web;

import com.ultibiz.core.tenant.TenantProvisionService;
import com.ultibiz.core.tenant.TenantService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The tenants: one database per organisation (P13). */
@Controller
@RequestMapping("/admin/settings/tenants")
public class TenantController {

    private final TenantService tenants;
    private final TenantProvisionService provision;

    public TenantController(TenantService tenants, TenantProvisionService provision) {
        this.tenants = tenants;
        this.provision = provision;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("tenants", tenants.all());
        model.addAttribute("perTenantDatabase", tenants.isPerTenantDatabase());
        return "admin/tenants";
    }

    @PostMapping("/provision")
    public String provision(@RequestParam("organizationCode") String organizationCode,
                            @RequestParam("databaseName") String databaseName,
                            @AuthenticationPrincipal com.ultibiz.web.security.UltiBizUserPrincipal principal,
                            RedirectAttributes redirectAttributes) {
        try {
            TenantProvisionService.Result result = this.provision.provision(organizationCode,
                    databaseName, principal.getUsername());
            redirectAttributes.addFlashAttribute("flash", "tenant.provisioned");
            redirectAttributes.addFlashAttribute("tenantProvisioned",
                    result.organizationCode() + " → " + result.databaseName());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/settings/tenants";
    }

    @PostMapping("/status")
    public String status(@RequestParam("organizationCode") String organizationCode,
                         @RequestParam("status") String status,
                         @AuthenticationPrincipal com.ultibiz.web.security.UltiBizUserPrincipal principal,
                         RedirectAttributes redirectAttributes) {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new IllegalArgumentException("The state " + status + " is not usable");
        }
        provision.setStatus(organizationCode, status, principal.getUsername());
        redirectAttributes.addFlashAttribute("flash", "tenant.status_updated");
        return "redirect:/admin/settings/tenants";
    }
}
