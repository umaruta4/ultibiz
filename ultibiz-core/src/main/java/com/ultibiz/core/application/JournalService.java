package com.ultibiz.core.application;

import com.ultibiz.core.domain.Account;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.FiscalPeriod;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.domain.JournalLine;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The posting engine.
 *
 * <p>A journal is posted only when it balances, its lines are valid, and its
 * fiscal period is open. The header, the lines and the document number are
 * written in one transaction, so a crash can never leave a half-posted
 * journal behind. Posted journals are immutable: a correction is a reversal.
 */
@Service
public class JournalService {

    private final JournalRepository journals;
    private final FiscalPeriodRepository periods;
    private final AccountRepository accounts;
    private final CountryConfigRepository countryConfig;
    private final NumberingService numbering;
    private final AuditService audit;
    private final FxService fx;
    private final TransactionTemplate tx;

    public JournalService(JournalRepository journals, FiscalPeriodRepository periods,
                           AccountRepository accounts, CountryConfigRepository countryConfig,
                           NumberingService numbering, AuditService audit, FxService fx,
                           TransactionTemplate tx) {
        this.journals = journals;
        this.periods = periods;
        this.accounts = accounts;
        this.countryConfig = countryConfig;
        this.numbering = numbering;
        this.audit = audit;
        this.fx = fx;
        // READ COMMITTED: the period lock and the numbering lock must always
        // see the latest committed row (see ADR-002).
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Posts a journal.
     *
     * @throws AccountingException when the journal does not balance, a line is
     *         invalid, the period is closed or unknown, or no rate exists
     */
    public JournalEntry post(SiteScope scope, String actor, JournalDraft draft) {
        if (draft == null) {
            throw new AccountingException("No journal data supplied");
        }
        if (draft.postingDate() == null) {
            throw new AccountingException("Posting date is required");
        }
        List<JournalLineDraft> lines = draft.lines();
        if (lines == null || lines.isEmpty()) {
            throw new AccountingException("A journal needs at least one line");
        }
        String country = draft.countryCode() == null || draft.countryCode().isBlank()
                ? "ID" : draft.countryCode().toUpperCase();

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (JournalLineDraft line : lines) {
            validateLine(scope, line);
            totalDebit = totalDebit.add(line.debitOrZero());
            totalCredit = totalCredit.add(line.creditOrZero());
        }
        totalDebit = totalDebit.setScale(2, RoundingMode.HALF_UP);
        totalCredit = totalCredit.setScale(2, RoundingMode.HALF_UP);
        if (totalDebit.signum() == 0 && totalCredit.signum() == 0) {
            throw new AccountingException("A journal needs a non-zero amount");
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new AccountingException("Journal does not balance: debit " + totalDebit
                    + " vs credit " + totalCredit);
        }

        FiscalPeriod period = periods.findByDate(scope.organizationId(), country, draft.postingDate());
        if (period == null) {
            throw new AccountingException("No fiscal period for " + country + " on " + draft.postingDate());
        }
        if (!period.isOpen()) {
            throw new AccountingException("Fiscal period " + period.fiscalYear() + "/"
                    + period.fiscalMonth() + " is closed");
        }

        String functional = countryConfig.find(scope.organizationId(), country)
                .map(c -> c.currency())
                .orElse(draft.currency());
        String currency = draft.currency() == null || draft.currency().isBlank()
                ? functional : draft.currency().toUpperCase();
        BigDecimal rate = draft.exchangeRate() != null ? draft.exchangeRate()
                : fx.rate(scope.organizationId(), functional, currency, draft.postingDate());

        final BigDecimal debitTotal = totalDebit;
        final BigDecimal creditTotal = totalCredit;
        final String journalCountry = country;
        final String journalCurrency = currency;
        final BigDecimal journalRate = rate;
        final String documentType = "REVERSAL".equalsIgnoreCase(draft.journalType()) ? "REV" : "JV";

        Long journalId = tx.execute(status -> {
            FiscalPeriod locked = periods.lock(period.id());
            if (locked == null || !locked.isOpen()) {
                throw new AccountingException("Fiscal period was closed while posting");
            }
            String journalNumber = numbering.next(documentType, scope);
            long id = journals.insertHeader(scope.organizationId(), draft.siteId(), journalCountry,
                    journalNumber, draft.journalType() == null ? "GENERAL" : draft.journalType().toUpperCase(),
                    draft.postingDate(), locked.id(), journalCurrency, journalRate, draft.reference(),
                    draft.sourceType(), draft.sourceId(), draft.reversesJournalId(), draft.description(),
                    debitTotal, creditTotal, actor, null);
            int lineNo = 1;
            for (JournalLineDraft line : lines) {
                BigDecimal debit = line.debitOrZero().setScale(2, RoundingMode.HALF_UP);
                BigDecimal credit = line.creditOrZero().setScale(2, RoundingMode.HALF_UP);
                journals.insertLine(id, lineNo++, line.accountId(), line.partyId(),
                        line.siteId() != null ? line.siteId() : draft.siteId(), line.description(),
                        debit, credit, journalCurrency, journalRate,
                        fx.convert(debit, journalRate), fx.convert(credit, journalRate),
                        line.taxCategoryId(), line.taxRate());
            }
            audit.record(AuditEvent.of(scope.organizationId(), actor, "JOURNAL_POST", "journal_entry",
                    String.valueOf(id),
                    "{\"number\":\"" + journalNumber + "\",\"debit\":" + debitTotal
                            + ",\"credit\":" + creditTotal + "}", null));
            return id;
        });

        JournalEntry posted = journals.findById(scope.organizationId(), journalId);
        if (posted == null) {
            throw new AccountingException("Journal disappeared after posting: " + journalId);
        }
        return posted;
    }

    /** Posts the mirror image of an existing journal and marks the original as reversed. */
    public JournalEntry reverse(SiteScope scope, String actor, long journalId) {
        JournalEntry original = journals.findById(scope.organizationId(), journalId);
        if (original == null) {
            throw new AccountingException("Journal not found: " + journalId);
        }
        if ("REVERSED".equalsIgnoreCase(original.status())) {
            throw new AccountingException("Journal " + original.journalNumber() + " is already reversed");
        }
        List<JournalLine> lines = journals.lines(journalId);
        List<JournalLineDraft> reversed = new ArrayList<>();
        for (JournalLine line : lines) {
            reversed.add(new JournalLineDraft(line.accountId(), line.partyId(), line.siteId(),
                    "Reversal of " + original.journalNumber()
                            + (line.description() == null ? "" : " — " + line.description()),
                    line.credit(), line.debit(), line.taxCategoryId(), line.taxRate()));
        }
        JournalEntry reversal = post(scope, actor, new JournalDraft(original.countryCode(), original.siteId(),
                "REVERSAL", LocalDate.now(), original.currency(), original.exchangeRate(),
                original.journalNumber(), "journal_entry", original.id(), original.id(),
                "Reversal of " + original.journalNumber(), reversed));
        journals.markReversed(original.id());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "JOURNAL_REVERSE", "journal_entry",
                String.valueOf(original.id()), "{\"reversal\":\"" + reversal.journalNumber() + "\"}", null));
        return reversal;
    }

    public JournalEntry byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return journals.findById(scope.organizationId(), id);
    }

    public List<JournalLine> lines(SiteScope scope, long journalId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return journals.lines(journalId);
    }

    public PageResult<JournalEntry> search(SiteScope scope, String countryCode, LocalDate from,
                                            LocalDate to, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = journals.count(scope.organizationId(), countryCode, from, to);
        List<JournalEntry> items = journals.list(scope.organizationId(), countryCode, from, to,
                safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    /** Trial balance in the functional currency. */
    public List<JournalRepository.TrialBalanceRow> trialBalance(SiteScope scope, LocalDate from, LocalDate to) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return journals.trialBalance(scope.organizationId(), from, to);
    }

    private void validateLine(SiteScope scope, JournalLineDraft line) {
        if (line == null) {
            throw new AccountingException("Empty journal line");
        }
        boolean debit = line.debitOrZero().signum() > 0;
        boolean credit = line.creditOrZero().signum() > 0;
        if (debit && credit) {
            throw new AccountingException("A line cannot be debit and credit at the same time");
        }
        if (!debit && !credit) {
            throw new AccountingException("A line needs a debit or a credit amount");
        }
        if (line.debitOrZero().signum() < 0 || line.creditOrZero().signum() < 0) {
            throw new AccountingException("Negative amounts are not allowed");
        }
        Account account = accounts.findById(scope.organizationId(), line.accountId());
        if (account == null) {
            throw new AccountingException("Unknown account: " + line.accountId());
        }
        if (!account.active() || !account.postable()) {
            throw new AccountingException("Account " + account.code() + " is not postable");
        }
    }
}
