package com.ultibiz.web.web;

import com.ultibiz.core.application.ApService;
import com.ultibiz.core.application.BankService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.ApBill;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Accounts payable: bills, payments, ageing and the three-way match. */
@Controller
@RequestMapping("/accounting/ap")
public class ApController {

    private final ApService ap;
    private final BankService banks;
    private final SiteScopeProvider scopeProvider;

    public ApController(ApService ap, BankService banks, SiteScopeProvider scopeProvider) {
        this.ap = ap;
        this.banks = banks;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String bills(@RequestParam(required = false) String status,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<ApBill> result = ap.searchBills(scope, status, page, size);
        model.addAttribute("bills", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        return "accounting/ap/bills";
    }

    @GetMapping("/aging")
    public String aging(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("report", ap.aging(scope, LocalDate.now()));
        return "accounting/ap/aging";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        ApBill bill = ap.billById(scope, id);
        if (bill == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Bill not found");
            model.addAttribute("message", "Bill " + id + " does not exist");
            return "error";
        }
        model.addAttribute("bill", bill);
        model.addAttribute("banks", banks.accounts(scope));
        model.addAttribute("today", LocalDate.now());
        return "accounting/ap/bill-detail";
    }

    @PostMapping("/{id}/payment")
    public String payment(@PathVariable long id,
                          @RequestParam BigDecimal amount,
                          @RequestParam(required = false) Long bankAccountId,
                          @RequestParam(required = false) String method,
                          @RequestParam(required = false) LocalDate paymentDate,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        SiteScope scope = scopeProvider.current();
        ApBill bill = ap.billById(scope, id);
        try {
            if (bill == null) {
                throw new OrderException("Bill not found: " + id);
            }
            ap.recordPayment(scope, actor(principal), bill.partyId(),
                    paymentDate == null ? LocalDate.now() : paymentDate, amount, "IDR",
                    method == null ? "TRANSFER" : method, bankAccountId, null, id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/accounting/ap/" + id;
    }

    @GetMapping("/{id}/match")
    public String match(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        ApService.MatchResult result = ap.threeWayMatch(scope, id);
        model.addAttribute("result", result);
        model.addAttribute("billId", id);
        return "accounting/ap/match";
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
