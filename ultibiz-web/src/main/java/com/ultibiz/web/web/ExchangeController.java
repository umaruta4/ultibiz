package com.ultibiz.web.web;

import com.ultibiz.core.application.ExchangeService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeMessage;
import com.ultibiz.core.exchange.ExchangePartner;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
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

/** The data-exchange console: partners, the document browser and the mapping test. */
@Controller
@RequestMapping("/exchange")
public class ExchangeController {

    private final ExchangeService exchange;
    private final SiteScopeProvider scopeProvider;

    public ExchangeController(ExchangeService exchange, SiteScopeProvider scopeProvider) {
        this.exchange = exchange;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String dashboard(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("counts", exchange.statusCounts(scope));
        model.addAttribute("messages", exchange.search(scope, null, null, null, 0, 15).items());
        model.addAttribute("partners", exchange.partners(scope));
        return "exchange/dashboard";
    }

    @GetMapping("/messages")
    public String messages(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String direction,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("messages", exchange.search(scope, status, direction, null, page, size));
        model.addAttribute("status", status);
        model.addAttribute("direction", direction);
        model.addAttribute("size", size);
        model.addAttribute("partners", partnerNames(scope));
        return "exchange/messages";
    }

    @GetMapping("/messages/{id}")
    public String message(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        ExchangeMessage message = exchange.byId(scope, id);
        if (message == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Not found");
            model.addAttribute("message", "Exchange message " + id + " does not exist");
            return "error";
        }
        model.addAttribute("message", message);
        return "exchange/message-detail";
    }

    @PostMapping("/messages/{id}/replay")
    public String replay(@PathVariable long id,
                         @AuthenticationPrincipal UltiBizUserPrincipal principal,
                         RedirectAttributes redirect) {
        try {
            exchange.replay(scopeProvider.current(), actor(principal), id);
            redirect.addFlashAttribute("flash", "exchange.replayed");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/exchange/messages/" + id;
    }

    @GetMapping("/partners")
    public String partners(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("partners", exchange.partners(scope));
        return "exchange/partners";
    }

    @PostMapping("/partners")
    public String registerPartner(@RequestParam String code,
                                  @RequestParam String name,
                                  @RequestParam String protocol,
                                  @RequestParam(defaultValue = "BOTH") String direction,
                                  @RequestParam(required = false) String endpointUrl,
                                  @RequestParam(defaultValue = "NONE") String authType,
                                  @RequestParam(required = false) String authReference,
                                  @RequestParam(required = false) String peppolParticipantId,
                                  @RequestParam(required = false) String ediInterchangeId,
                                  @RequestParam(required = false) String ediQualifier,
                                  @RequestParam(required = false) String mappingConfig,
                                  @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  RedirectAttributes redirect) {
        try {
            exchange.registerPartner(scopeProvider.current(), actor(principal), code, name, protocol,
                    direction, endpointUrl, authType, authReference, peppolParticipantId,
                    ediInterchangeId, ediQualifier, mappingConfig);
            redirect.addFlashAttribute("flash", "exchange.partnerSaved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/exchange/partners";
    }

    @GetMapping("/mapping")
    public String mapping(@RequestParam(required = false) String partnerCode,
                          @RequestParam(required = false) String payload,
                          Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("partners", exchange.partners(scope));
        model.addAttribute("partnerCode", partnerCode);
        model.addAttribute("payload", payload);
        model.addAttribute("result", null);
        return "exchange/mapping";
    }

    @PostMapping("/mapping/test")
    public String mappingTest(@RequestParam String partnerCode,
                               @RequestParam String payload,
                               Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("partners", exchange.partners(scope));
        model.addAttribute("partnerCode", partnerCode);
        model.addAttribute("payload", payload);
        MappingOutcome outcome = new MappingOutcome();
        outcome.partnerCode = partnerCode;
        for (ExchangePartner partner : exchange.partners(scope)) {
            if (partner.code().equals(partnerCode)) {
                try {
                    CanonicalDocument document = exchange.preview(scope, partnerCode, payload);
                    outcome.document = document;
                    outcome.issues = exchange.previewIssues(scope, partnerCode, document);
                    outcome.ok = true;
                } catch (RuntimeException ex) {
                    outcome.error = ex.getMessage();
                }
                break;
            }
        }
        model.addAttribute("result", outcome);
        return "exchange/mapping";
    }

    /** The outcome of a mapping test run. */
    public static final class MappingOutcome {
        public String partnerCode;
        public boolean ok;
        public String error;
        public CanonicalDocument document;
        public List<com.ultibiz.core.exchange.ValidationIssue> issues = List.of();
    }

    private java.util.Map<Long, String> partnerNames(SiteScope scope) {
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (ExchangePartner partner : exchange.partners(scope)) {
            names.put(partner.id(), partner.code());
        }
        return names;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
