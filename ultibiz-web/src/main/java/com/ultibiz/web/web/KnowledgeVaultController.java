package com.ultibiz.web.web;

import com.ultibiz.core.application.KnowledgeVaultService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The Knowledge Vault: the documents, their versions, the folders and the search. */
@Controller
@RequestMapping("/knowledge")
public class KnowledgeVaultController {

    private final KnowledgeVaultService vault;
    private final SiteScopeProvider scopeProvider;

    public KnowledgeVaultController(KnowledgeVaultService vault, SiteScopeProvider scopeProvider) {
        this.vault = vault;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) Long folderId,
                       Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("documents", vault.documents(scope, folderId));
        model.addAttribute("folders", vault.folders(scope));
        model.addAttribute("tags", vault.tags(scope));
        model.addAttribute("policies", vault.retentionPolicies(scope));
        model.addAttribute("folderId", folderId);
        model.addAttribute("query", q);
        model.addAttribute("results", q == null || q.isBlank() ? List.of() : vault.search(scope, q, 20));
        return "knowledge/list";
    }

    @GetMapping("/{id}")
    public String document(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("document", vault.document(scope, id));
        model.addAttribute("versions", vault.versions(scope, id));
        model.addAttribute("tags", vault.tagsOf(id));
        return "knowledge/document";
    }

    @PostMapping
    public String create(@RequestParam String code,
                         @RequestParam String title,
                         @RequestParam(required = false) String source,
                         @RequestParam String content,
                         @RequestParam(required = false) Long folderId,
                         @RequestParam(required = false) String tags,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            long id = vault.createDocument(scopeProvider.current(), actor(principal), code, title,
                    source, "id", content, folderId, null, splitTags(tags));
            redirect.addFlashAttribute("flash", "kv.documentCreated");
            return "redirect:/knowledge/" + id;
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/knowledge";
        }
    }

    @PostMapping("/{id}/versions")
    public String addVersion(@PathVariable long id,
                             @RequestParam String title,
                             @RequestParam String content,
                             @RequestParam(required = false) String note,
                             @AuthenticationPrincipal UltiBizUserPrincipal principal,
                             RedirectAttributes redirect) {
        try {
            vault.addVersion(scopeProvider.current(), actor(principal), id, title, content, note);
            redirect.addFlashAttribute("flash", "kv.versionAdded");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/knowledge/" + id;
    }

    @PostMapping("/{id}/hold")
    public String legalHold(@PathVariable long id,
                            @RequestParam(defaultValue = "true") boolean hold,
                            @AuthenticationPrincipal UltiBizUserPrincipal principal,
                            RedirectAttributes redirect) {
        try {
            vault.setLegalHold(scopeProvider.current(), actor(principal), id, hold);
            redirect.addFlashAttribute("flash", hold ? "kv.holdSet" : "kv.holdReleased");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/knowledge/" + id;
    }

    @PostMapping("/folders")
    public String createFolder(@RequestParam String code,
                               @RequestParam String name,
                               @RequestParam(required = false) Long parentId,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        try {
            vault.createFolder(scopeProvider.current(), actor(principal), code, name, parentId);
            redirect.addFlashAttribute("flash", "kv.folderCreated");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/knowledge";
    }

    @PostMapping("/retention/run")
    public String runRetention(@AuthenticationPrincipal UltiBizUserPrincipal principal,
                               RedirectAttributes redirect) {
        try {
            var result = vault.runRetention(scopeProvider.current(), actor(principal));
            redirect.addFlashAttribute("flash", "kv.retentionRun");
            redirect.addFlashAttribute("archived", result.archived());
            redirect.addFlashAttribute("deleted", result.deleted());
            redirect.addFlashAttribute("held", result.held());
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/knowledge";
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isEmpty())
                .toList();
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
