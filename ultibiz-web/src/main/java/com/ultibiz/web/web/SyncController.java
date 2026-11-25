package com.ultibiz.web.web;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SyncService;
import com.ultibiz.core.infrastructure.SyncRepository.Conflict;
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

/** The sync console: the offline documents, the conflict centre and the manual sync. */
@Controller
@RequestMapping("/sync")
public class SyncController {

    private final SyncService sync;
    private final SiteScopeProvider scopeProvider;

    public SyncController(SyncService sync, SiteScopeProvider scopeProvider) {
        this.sync = sync;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String status(@RequestParam(required = false) String entity, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("documents", sync.documents(scope, entity));
        model.addAttribute("entity", entity);
        model.addAttribute("cursor", sync.cursor(scope));
        model.addAttribute("conflicts", sync.conflicts(scope, "OPEN"));
        model.addAttribute("onlineOnly", com.ultibiz.core.sync.OfflinePolicy.ONLINE_ONLY);
        return "sync/status";
    }

    @GetMapping("/conflicts")
    public String conflicts(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("conflicts", sync.conflicts(scope, null));
        return "sync/conflicts";
    }

    @PostMapping("/conflicts/{id}/resolve")
    public String resolve(@PathVariable long id,
                          @RequestParam String resolution,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        try {
            sync.resolveConflict(scopeProvider.current(), actor(principal), id, resolution);
            redirect.addFlashAttribute("flash", "sync.conflictResolved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/sync/conflicts";
    }

    @PostMapping("/now")
    public String syncNow(@AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        int handled = sync.retryQueued(scopeProvider.current(), actor(principal));
        redirect.addFlashAttribute("flash", "sync.manualRun");
        redirect.addFlashAttribute("handled", handled);
        return "redirect:/sync";
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
