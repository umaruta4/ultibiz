package com.ultibiz.web.web;

import com.ultibiz.core.application.HelpdeskService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The helpdesk console: the queues, the tickets, the replies and the SLA. */
@Controller
@RequestMapping("/helpdesk")
public class HelpdeskController {

    private final HelpdeskService helpdesk;
    private final SiteScopeProvider scopeProvider;

    public HelpdeskController(HelpdeskService helpdesk, SiteScopeProvider scopeProvider) {
        this.helpdesk = helpdesk;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) Long queueId,
                       Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("tickets", helpdesk.tickets(scope, status, queueId));
        model.addAttribute("queues", helpdesk.queues(scope));
        model.addAttribute("status", status);
        model.addAttribute("queueId", queueId);
        return "helpdesk/list";
    }

    @PostMapping
    public String intake(@RequestParam String subject,
                         @RequestParam String description,
                         @RequestParam(required = false) String category,
                         @RequestParam(required = false) Long queueId,
                         @RequestParam(required = false) String requesterName,
                         @RequestParam(required = false) String requesterEmail,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String priority,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            long id = helpdesk.intake(scopeProvider.current(), actor(principal), subject,
                    description, category, queueId, requesterName, requesterEmail, null, source,
                    priority);
            redirect.addFlashAttribute("flash", "hd.ticketCreated");
            return "redirect:/helpdesk/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/helpdesk";
        }
    }

    @GetMapping("/{id}")
    public String ticket(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("ticket", helpdesk.ticket(scope, id));
        model.addAttribute("activities", helpdesk.activities(scope, id, false));
        model.addAttribute("suggestions", helpdesk.suggestions(scope, id));
        model.addAttribute("queues", helpdesk.queues(scope));
        model.addAttribute("sla", helpdesk.slaState(scope, id, java.time.LocalDateTime.now()));
        return "helpdesk/ticket";
    }

    @PostMapping("/{id}/reply")
    public String reply(@PathVariable long id,
                        @RequestParam String body,
                        @RequestParam(defaultValue = "true") boolean isPublic,
                        @AuthenticationPrincipal UltiBizUserPrincipal principal,
                        RedirectAttributes redirect) {
        try {
            helpdesk.reply(scopeProvider.current(), actor(principal), id, body, isPublic);
            redirect.addFlashAttribute("flash", "hd.replySent");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/assign")
    public String assign(@PathVariable long id,
                         @RequestParam long assigneeId,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            helpdesk.assign(scopeProvider.current(), actor(principal), id, assigneeId);
            redirect.addFlashAttribute("flash", "hd.assigned");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/triage")
    public String triage(@PathVariable long id,
                         @RequestParam(required = false) String priority,
                         @RequestParam(required = false) String category,
                         @RequestParam(required = false) Long queueId,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            helpdesk.triage(scopeProvider.current(), actor(principal), id, priority, category,
                    queueId);
            redirect.addFlashAttribute("flash", "hd.triaged");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable long id,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            helpdesk.resolve(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "hd.resolved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/close")
    public String close(@PathVariable long id,
                        @AuthenticationPrincipal UltiBizUserPrincipal principal,
                        RedirectAttributes redirect) {
        try {
            helpdesk.close(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "hd.closed");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/escalate")
    public String escalate(@PathVariable long id,
                           @RequestParam String reason,
                           @AuthenticationPrincipal UltiBizUserPrincipal principal,
                           RedirectAttributes redirect) {
        try {
            helpdesk.escalate(scopeProvider.current(), actor(principal), id, reason);
            redirect.addFlashAttribute("flash", "hd.escalated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    @PostMapping("/{id}/suggest")
    public String suggest(@PathVariable long id,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            int count = helpdesk.suggestKnowledge(scopeProvider.current(), id);
            redirect.addFlashAttribute("flash", "hd.suggestionsReady");
            redirect.addFlashAttribute("suggestions", count);
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/helpdesk/" + id;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
