package com.ultibiz.web.web;

import com.ultibiz.core.application.CrmService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.CrmRepository;
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

/** The CRM console: the pipeline board, the leads, the opportunities and the activities. */
@Controller
@RequestMapping("/crm")
public class CrmController {

    private final CrmService crm;
    private final SiteScopeProvider scopeProvider;

    public CrmController(CrmService crm, SiteScopeProvider scopeProvider) {
        this.crm = crm;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String board(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("stages", crm.stages(scope));
        model.addAttribute("board", crm.pipelineBoard(scope));
        model.addAttribute("weighted", crm.weightedPipeline(scope));
        model.addAttribute("activities", crm.activities(scope, true));
        model.addAttribute("opportunities", crm.opportunities(scope, null));
        return "crm/board";
    }

    @GetMapping("/leads")
    public String leads(@RequestParam(required = false) String status, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("leads", crm.leads(scope, status));
        model.addAttribute("territories", crm.territories(scope));
        model.addAttribute("campaigns", crm.campaigns(scope));
        model.addAttribute("status", status);
        return "crm/leads";
    }

    @PostMapping("/leads")
    public String createLead(@RequestParam String code,
                             @RequestParam String company,
                             @RequestParam(required = false) String contactName,
                             @RequestParam(required = false) String email,
                             @RequestParam(required = false) String phone,
                             @RequestParam(required = false) String source,
                             @RequestParam(required = false) Long territoryId,
                             @RequestParam(required = false) Long campaignId,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            crm.createLead(scopeProvider.current(), actor(principal), code, company, contactName,
                    email, phone, source, territoryId, campaignId);
            redirect.addFlashAttribute("flash", "crm.leadCreated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/leads";
    }

    @PostMapping("/leads/{id}/qualify")
    public String qualify(@PathVariable long id,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            crm.qualifyLead(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "crm.leadQualified");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/leads";
    }

    @PostMapping("/leads/{id}/convert")
    public String convert(@PathVariable long id,
                          @RequestParam(required = false) String opportunityName,
                          @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedClose,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            crm.convertLead(scopeProvider.current(), actor(principal), id, opportunityName,
                    amount, expectedClose);
            redirect.addFlashAttribute("flash", "crm.leadConverted");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/leads";
    }

    @PostMapping("/leads/{id}/lose")
    public String lose(@PathVariable long id,
                       @RequestParam String reason,
                       @AuthenticationPrincipal UltiBizUserPrincipal principal,
                       RedirectAttributes redirect) {
        try {
            crm.loseLead(scopeProvider.current(), actor(principal), id, reason);
            redirect.addFlashAttribute("flash", "crm.leadLost");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/leads";
    }

    @GetMapping("/opportunities")
    public String opportunities(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("opportunities", crm.opportunities(scope, null));
        model.addAttribute("stages", crm.stages(scope));
        return "crm/opportunities";
    }

    @PostMapping("/opportunities/{id}/stage")
    public String move(@PathVariable long id,
                       @RequestParam String stage,
                       @RequestParam(required = false) String lostReason,
                       @AuthenticationPrincipal UltiBizUserPrincipal principal,
                       RedirectAttributes redirect) {
        try {
            crm.moveOpportunity(scopeProvider.current(), actor(principal), id, stage, lostReason);
            redirect.addFlashAttribute("flash", "crm.opportunityMoved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/opportunities";
    }

    @PostMapping("/opportunities/{id}/handoff")
    public String handoff(@PathVariable long id,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            crm.handOffToSales(scopeProvider.current(), actor(principal), id, null);
            redirect.addFlashAttribute("flash", "crm.handedOff");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm/opportunities";
    }

    @PostMapping("/activities")
    public String logActivity(@RequestParam(required = false) Long opportunityId,
                              @RequestParam String type,
                              @RequestParam String subject,
                              @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                              @RequestParam(required = false) String notes,
                              @AuthenticationPrincipal UltiBizUserPrincipal principal,
                              RedirectAttributes redirect) {
        try {
            crm.logActivity(scopeProvider.current(), actor(principal), opportunityId, null, null,
                    type, subject, dueDate, notes);
            redirect.addFlashAttribute("flash", "crm.activityLogged");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/crm";
    }

    @PostMapping("/activities/{id}/done")
    public void completeActivity(@PathVariable long id,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal) {
        crm.completeActivity(scopeProvider.current(), actor(principal), id);
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
