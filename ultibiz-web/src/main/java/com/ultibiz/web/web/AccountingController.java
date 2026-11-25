package com.ultibiz.web.web;

import com.ultibiz.core.application.AccountService;
import com.ultibiz.core.application.AccountingException;
import com.ultibiz.core.application.FiscalPeriodService;
import com.ultibiz.core.application.JournalDraft;
import com.ultibiz.core.application.JournalLineDraft;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.PageResult;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.Account;
import com.ultibiz.core.domain.FiscalPeriod;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.domain.JournalLine;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.web.security.SiteScopeProvider;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Accounting screens: chart of accounts, journal editor, fiscal periods and
 * the trial balance.
 */
@Controller
@RequestMapping("/accounting")
public class AccountingController {

    private static final int FORM_LINES = 8;
    private static final Pattern LINE_PATTERN =
            Pattern.compile("^line(\\d+)(AccountId|Debit|Credit|Description)$");
    private static final String DEFAULT_COUNTRY = "ID";

    private final AccountService accounts;
    private final JournalService journals;
    private final FiscalPeriodService periods;
    private final SiteScopeProvider scopeProvider;

    public AccountingController(AccountService accounts, JournalService journals,
                                FiscalPeriodService periods, SiteScopeProvider scopeProvider) {
        this.accounts = accounts;
        this.journals = journals;
        this.periods = periods;
        this.scopeProvider = scopeProvider;
    }

    // ------------------------------------------------------ chart of accounts

    @GetMapping("/accounts")
    public String accounts(@RequestParam(defaultValue = "false") boolean showInactive, Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("rows", accounts.rows(scope, !showInactive));
        model.addAttribute("showInactive", showInactive);
        return "accounting/accounts";
    }

    // -------------------------------------------------------------- journals

    @GetMapping("/journals")
    public String journals(@RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(required = false) String country,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size,
                            Model model) {
        SiteScope scope = scopeProvider.current();
        PageResult<JournalEntry> result = journals.search(scope, country, from, to, page, size);
        model.addAttribute("journals", result);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("country", country);
        model.addAttribute("size", size);
        return "accounting/journals";
    }

    @GetMapping("/journals/new")
    public String newJournal(Model model) {
        SiteScope scope = scopeProvider.current();
        model.addAttribute("accounts", accounts.postable(scope));
        model.addAttribute("country", DEFAULT_COUNTRY);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("lineCount", FORM_LINES);
        return "accounting/journal-form";
    }

    @PostMapping("/journals")
    public String postJournal(@RequestParam Map<String, String> params,
                               @RequestParam(defaultValue = "GENERAL") String journalType,
                               @RequestParam(defaultValue = DEFAULT_COUNTRY) String country,
                               @RequestParam(required = false) LocalDate postingDate,
                               @RequestParam(required = false) String currency,
                               @RequestParam(required = false) String reference,
                               @RequestParam(required = false) String description,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               Model model) {
        SiteScope scope = scopeProvider.current();
        List<JournalLineDraft> lines = parseLines(params);
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JournalLineDraft line : lines) {
            debit = debit.add(line.debitOrZero());
            credit = credit.add(line.creditOrZero());
        }
        model.addAttribute("accounts", accounts.postable(scope));
        model.addAttribute("country", country);
        model.addAttribute("today", postingDate == null ? LocalDate.now() : postingDate);
        model.addAttribute("lineCount", FORM_LINES);
        model.addAttribute("totalDebit", debit);
        model.addAttribute("totalCredit", credit);

        try {
            JournalDraft draft = new JournalDraft(country.toUpperCase(), null, journalType.toUpperCase(),
                    postingDate == null ? LocalDate.now() : postingDate,
                    currency == null || currency.isBlank() ? "IDR" : currency.toUpperCase(),
                    BigDecimal.ONE, reference, null, null, null, description, lines);
            JournalEntry posted = journals.post(scope, actor(principal), draft);
            return "redirect:/accounting/journals/" + posted.id();
        } catch (AccountingException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("journalType", journalType);
            model.addAttribute("reference", reference);
            model.addAttribute("description", description);
            model.addAttribute("currency", currency);
            return "accounting/journal-form";
        }
    }

    @GetMapping("/journals/{id}")
    public String journalDetail(@PathVariable long id, Model model) {
        SiteScope scope = scopeProvider.current();
        JournalEntry journal = journals.byId(scope, id);
        if (journal == null) {
            model.addAttribute("status", 404);
            model.addAttribute("error", "Journal not found");
            model.addAttribute("message", "Journal " + id + " does not exist");
            return "error";
        }
        List<JournalLine> lines = journals.lines(scope, id);
        Map<Long, String> accountNames = new HashMap<>();
        for (Account account : accounts.list(scope, false)) {
            accountNames.put(account.id(), account.code() + " — " + account.name());
        }
        model.addAttribute("journal", journal);
        model.addAttribute("lines", lines);
        model.addAttribute("accountNames", accountNames);
        return "accounting/journal-detail";
    }

    @PostMapping("/journals/{id}/reverse")
    public String reverseJournal(@PathVariable long id,
                                 @AuthenticationPrincipal UltiBizUserPrincipal principal,
                                 Model model) {
        SiteScope scope = scopeProvider.current();
        try {
            journals.reverse(scope, actor(principal), id);
            return "redirect:/accounting/journals/" + id;
        } catch (AccountingException ex) {
            model.addAttribute("status", 409);
            model.addAttribute("error", "Conflict");
            model.addAttribute("message", ex.getMessage());
            return "error";
        }
    }

    // ------------------------------------------------------- fiscal periods

    @GetMapping("/periods")
    public String periods(@RequestParam(required = false) String country, Model model) {
        SiteScope scope = scopeProvider.current();
        String countryCode = country == null || country.isBlank() ? DEFAULT_COUNTRY : country;
        List<FiscalPeriod> periodList = periods.list(scope, countryCode);
        model.addAttribute("periods", periodList);
        model.addAttribute("country", countryCode);
        return "accounting/periods";
    }

    @PostMapping("/periods/{id}/close")
    public String closePeriod(@PathVariable long id,
                              @AuthenticationPrincipal UltiBizUserPrincipal principal,
                              Model model) {
        return periodAction(id, actor(principal), model, true);
    }

    @PostMapping("/periods/{id}/reopen")
    public String reopenPeriod(@PathVariable long id,
                               @AuthenticationPrincipal UltiBizUserPrincipal principal,
                               Model model) {
        return periodAction(id, actor(principal), model, false);
    }

    private String periodAction(long id, String actor, Model model, boolean close) {
        SiteScope scope = scopeProvider.current();
        try {
            if (close) {
                periods.close(scope, actor, id);
            } else {
                periods.reopen(scope, actor, id);
            }
            return "redirect:/accounting/periods";
        } catch (AccountingException ex) {
            model.addAttribute("periods", periods.list(scope, DEFAULT_COUNTRY));
            model.addAttribute("country", DEFAULT_COUNTRY);
            model.addAttribute("error", ex.getMessage());
            return "accounting/periods";
        }
    }

    // -------------------------------------------------------- trial balance

    @GetMapping("/trial-balance")
    public String trialBalance(@RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                Model model) {
        SiteScope scope = scopeProvider.current();
        List<JournalRepository.TrialBalanceRow> rows = journals.trialBalance(scope, from, to);
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (JournalRepository.TrialBalanceRow row : rows) {
            totalDebit = totalDebit.add(row.totalDebit());
            totalCredit = totalCredit.add(row.totalCredit());
        }
        model.addAttribute("rows", rows);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("totalDebit", totalDebit);
        model.addAttribute("totalCredit", totalCredit);
        model.addAttribute("balanced", totalDebit.compareTo(totalCredit) == 0);
        return "accounting/trial-balance";
    }

    // ------------------------------------------------------------- helpers

    /** Builds the lines from the dynamic form fields {@code lineNAccountId} etc. */
    private List<JournalLineDraft> parseLines(Map<String, String> params) {
        Map<Integer, Map<String, String>> byLine = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            Matcher matcher = LINE_PATTERN.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int index = Integer.parseInt(matcher.group(1));
            byLine.computeIfAbsent(index, k -> new HashMap<>())
                    .put(matcher.group(2), entry.getValue());
        }
        List<JournalLineDraft> lines = new ArrayList<>();
        for (int index = 1; index <= FORM_LINES; index++) {
            Map<String, String> fields = byLine.get(index);
            if (fields == null) {
                continue;
            }
            String accountId = trimToNull(fields.get("AccountId"));
            if (accountId == null) {
                continue;
            }
            BigDecimal debit = parseAmount(fields.get("Debit"));
            BigDecimal credit = parseAmount(fields.get("Credit"));
            if (debit.signum() == 0 && credit.signum() == 0) {
                continue;
            }
            lines.add(new JournalLineDraft(Long.parseLong(accountId), null, null,
                    trimToNull(fields.get("Description")), debit, credit, null, null));
        }
        return lines;
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String actor(UltiBizUserPrincipal principal) {
        return principal != null ? principal.displayName() : "system";
    }
}
