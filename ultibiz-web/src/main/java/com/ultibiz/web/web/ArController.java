package com.ultibiz.web.web;

import com.ultibiz.core.application.ArService;
import com.ultibiz.core.application.BankService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.ArInvoice;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Accounts receivable: invoices, receipts and the ageing report. */
@Controller
@RequestMapping("/accounting/ar")
public class ArController {

    private final ArService ar;
    private final BankService banks;
    private final SiteScopeProvider scopeProvider;

    public ArController(ArService ar, BankService banks, SiteScopeProvider scopeProvider) {
        this.ar = ar;
        this.banks = banks;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String invoices(@RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size,
                            Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<ArInvoice> result = ar.searchInvoices(scope, status, page, size);
        model.addAttribute("invoices", result);
        model.addAttribute("status", status);
        model.addAttribute("size", size);
        return "accounting/ar/invoices";
    }

    @GetMapping("/aging")
    public String aging(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("report", ar.aging(scope, LocalDate.now()));
        return "accounting/ar/aging";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        ArInvoice invoice = ar.invoiceById(scope, id);
        if (invoice == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Invoice not found");
            model.addAttribute("message", "Invoice " + id + " does not exist");
            return "error";
        }
        model.addAttribute("invoice", invoice);
        model.addAttribute("banks", banks.accounts(scope));
        model.addAttribute("today", LocalDate.now());
        return "accounting/ar/invoice-detail";
    }

    @PostMapping("/{id}/receipt")
    public String receipt(@PathVariable long id,
                          @RequestParam BigDecimal amount,
                          @RequestParam(required = false) Long bankAccountId,
                          @RequestParam(required = false) String method,
                          @RequestParam(required = false) LocalDate receiptDate,
                          @AuthenticationPrincipal UltiBizUserPrincipal principal,
                          RedirectAttributes redirect) {
        SiteScope scope = scopeProvider.current();
        ArInvoice invoice = ar.invoiceById(scope, id);
        try {
            if (invoice == null) {
                throw new OrderException("Invoice not found: " + id);
            }
            ar.recordReceipt(scope, actor(principal), invoice.partyId(),
                    receiptDate == null ? LocalDate.now() : receiptDate, amount, "IDR",
                    method == null ? "TRANSFER" : method, bankAccountId, null, id);
        } catch (OrderException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/accounting/ar/" + id;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
