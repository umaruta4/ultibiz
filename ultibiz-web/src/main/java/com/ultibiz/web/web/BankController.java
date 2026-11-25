package com.ultibiz.web.web;

import com.ultibiz.core.application.BankService;
import com.ultibiz.core.application.OrderException;
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

/** Bank and cash: accounts, statement import and reconciliation. */
@Controller
@RequestMapping("/accounting/bank")
public class BankController {

    private final BankService banks;
    private final SiteScopeProvider scopeProvider;

    public BankController(BankService banks, SiteScopeProvider scopeProvider) {
        this.banks = banks;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String accounts(Model model) {
        model.addAttribute("accounts", banks.accounts(scopeProvider.current()));
        return "accounting/bank/accounts";
    }

    @GetMapping("/{id}")
    public String account(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("account", banks.accounts(scope).stream()
                .filter(a -> a.id() == id).findFirst().orElse(null));
        model.addAttribute("lines", banks.statementLines(scope, id));
        return "accounting/bank/account-detail";
    }

    @PostMapping("/{id}/import")
    public String importStatement(@PathVariable long id,
                                  @RequestParam("csv") String csv,
                                  @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                  RedirectAttributes redirect) {
        try {
            banks.importStatement(scopeProvider.current(), actor(principal), id, csv);
            redirect.addFlashAttribute("flash", "acct.imported");
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/accounting/bank/" + id;
    }

    @PostMapping("/{id}/reconcile")
    public String reconcile(@PathVariable long id,
                            @AuthenticationPrincipal UltiBizUserPrincipal principal,
                            RedirectAttributes redirect) {
        BankService.ReconcileReport report =
                banks.reconcile(scopeProvider.current(), actor(principal), id);
        redirect.addFlashAttribute("message", "Reconciled: " + report.matched()
                + " matched, " + report.unmatched() + " unmatched");
        return "redirect:/accounting/bank/" + id;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
