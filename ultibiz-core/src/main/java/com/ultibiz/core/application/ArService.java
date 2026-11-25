package com.ultibiz.core.application;

import com.ultibiz.core.domain.Account;
import com.ultibiz.core.domain.ArInvoice;
import com.ultibiz.core.domain.ArReceipt;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.BankAccount;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.infrastructure.ArRepository;
import com.ultibiz.core.infrastructure.BankRepository;
import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxPackContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Accounts receivable: issue an invoice (posts the revenue and the tax),
 * record a receipt (posts the cash) and allocate it against open invoices.
 */
@Service
public class ArService {

    private final ArRepository ar;
    private final AccountService accounts;
    private final JournalService journals;
    private final TaxEngine taxEngine;
    private final NumberingService numbering;
    private final TaxRuleRepository taxRules;
    private final BankRepository banks;
    private final AuditService audit;

    public ArService(ArRepository ar, AccountService accounts, JournalService journals, TaxEngine taxEngine,
                     NumberingService numbering, TaxRuleRepository taxRules, BankRepository banks,
                     AuditService audit) {
        this.ar = ar;
        this.accounts = accounts;
        this.journals = journals;
        this.taxEngine = taxEngine;
        this.numbering = numbering;
        this.taxRules = taxRules;
        this.banks = banks;
        this.audit = audit;
    }

    public ArInvoice issueInvoice(SiteScope scope, String actor, String countryCode, long partyId,
                                   Long salesOrderId, LocalDate invoiceDate, LocalDate dueDate, String currency,
                                   BigDecimal exchangeRate, boolean taxInclusive,
                                   List<SalesOrderLineDraft> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new OrderException("An invoice needs at least one line");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        List<BigDecimal> nets = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        for (SalesOrderLineDraft draft : lines) {
            BigDecimal net = SalesOrderService.netAmount(draft);
            nets.add(net);
            categories.add(draft.taxCategoryId() == null ? null
                    : taxRules.categoryCode(draft.taxCategoryId()).orElse(null));
            subtotal = subtotal.add(net);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        String country = countryCode == null ? "ID" : countryCode.toUpperCase();
        List<TaxPack.TaxableAmount> taxable = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            taxable.add(new TaxPack.TaxableAmount(categories.get(i), nets.get(i), nets.get(i),
                    lines.get(i).description()));
        }
        TaxPackContext context = new TaxPackContext(scope.organizationId(), country,
                currency == null ? "IDR" : currency, invoiceDate, taxInclusive, RoundingMode.HALF_UP,
                TaxPackContext.RoundingLevel.LINE, 2);
        List<TaxLine> taxLines = taxEngine.compute(scope.organizationId(), country, taxable, context);
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (TaxLine taxLine : taxLines) {
            taxTotal = taxTotal.add(taxLine.taxAmount());
        }
        taxTotal = taxTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(taxTotal).setScale(2, RoundingMode.HALF_UP);

        Account receivable = requireAccount(scope, "1200", "Accounts Receivable");
        Account revenue = requireAccount(scope, "4100", "Sales Revenue");
        Account taxPayable = taxTotal.signum() > 0 ? requireAccount(scope, "2110", "Tax Payable") : null;

        List<JournalLineDraft> journalLines = new ArrayList<>();
        journalLines.add(new JournalLineDraft(receivable.id(), partyId, null, "Invoice", total,
                BigDecimal.ZERO, null, null));
        journalLines.add(new JournalLineDraft(revenue.id(), null, null, "Revenue", BigDecimal.ZERO,
                subtotal, null, null));
        if (taxPayable != null) {
            journalLines.add(new JournalLineDraft(taxPayable.id(), null, null, "PPN payable",
                    BigDecimal.ZERO, taxTotal, null, null));
        }

        JournalEntry journal = journals.post(scope, actor, new JournalDraft(country, null, "SALES",
                invoiceDate, currency == null ? "IDR" : currency,
                exchangeRate == null ? BigDecimal.ONE : exchangeRate,
                null, "ar_invoice", null, null, "Invoice", journalLines));

        String invoiceNumber = numbering.next("INV", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long invoiceId = ar.insertInvoice(scope.organizationId(), siteId, country, invoiceNumber, partyId,
                salesOrderId, invoiceDate, dueDate, currency == null ? "IDR" : currency,
                exchangeRate == null ? BigDecimal.ONE : exchangeRate, taxInclusive, subtotal, taxTotal, total,
                journal.id(), actor);

        int lineNo = 1;
        for (int i = 0; i < lines.size(); i++) {
            SalesOrderLineDraft draft = lines.get(i);
            BigDecimal net = nets.get(i);
            BigDecimal tax = i < taxLines.size() ? taxLines.get(i).taxAmount() : BigDecimal.ZERO;
            ar.insertInvoiceLine(invoiceId, lineNo++, draft.productId(),
                    draft.description() == null ? "" : draft.description(), draft.quantity(), draft.unitPrice(),
                    draft.discount() == null ? BigDecimal.ZERO : draft.discount(), draft.taxCategoryId(),
                    i < taxLines.size() ? taxLines.get(i).rate() : null, net, tax,
                    net.add(tax).setScale(2, RoundingMode.HALF_UP));
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AR_INVOICE_ISSUE", "ar_invoice",
                String.valueOf(invoiceId), "{\"number\":\"" + invoiceNumber + "\"}", null));
        return ar.findInvoice(scope.organizationId(), invoiceId);
    }

    /** Records money received and allocates it against the open invoices, oldest due first. */
    public ArReceipt recordReceipt(SiteScope scope, String actor, long partyId, LocalDate receiptDate,
                                   BigDecimal amount, String currency, String method, Long bankAccountId,
                                   String reference) {
        return recordReceipt(scope, actor, partyId, receiptDate, amount, currency, method, bankAccountId,
                reference, null);
    }

    /**
     * Records money received. With {@code targetInvoiceId} the amount is
     * allocated to that invoice first; the remainder goes to the oldest open
     * invoices of the same party.
     */
    public ArReceipt recordReceipt(SiteScope scope, String actor, long partyId, LocalDate receiptDate,
                                   BigDecimal amount, String currency, String method, Long bankAccountId,
                                   String reference, Long targetInvoiceId) {
        if (amount == null || amount.signum() <= 0) {
            throw new OrderException("A receipt needs a positive amount");
        }
        Account receivable = requireAccount(scope, "1200", "Accounts Receivable");
        long cashAccountId = bankAccountId != null
                ? bankAccountGl(scope, bankAccountId)
                : requireAccount(scope, "1100", "Cash on Hand").id();

        List<JournalLineDraft> journalLines = new ArrayList<>();
        journalLines.add(new JournalLineDraft(cashAccountId, null, null, "Receipt from " + partyId,
                amount, BigDecimal.ZERO, null, null));
        journalLines.add(new JournalLineDraft(receivable.id(), partyId, null, "Receipt from " + partyId,
                BigDecimal.ZERO, amount, null, null));

        JournalEntry journal = journals.post(scope, actor, new JournalDraft("ID", null, "RECEIPT",
                receiptDate, currency == null ? "IDR" : currency, BigDecimal.ONE, reference,
                "ar_receipt", null, null, "Customer receipt", journalLines));

        String receiptNumber = numbering.next("RCPT", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long receiptId = ar.insertReceipt(scope.organizationId(), siteId, receiptNumber, partyId, receiptDate,
                amount, currency == null ? "IDR" : currency, method == null ? "TRANSFER" : method,
                bankAccountId, journal.id(), reference, actor);

        BigDecimal remaining = amount;
        if (targetInvoiceId != null) {
            ArInvoice target = ar.findInvoice(scope.organizationId(), targetInvoiceId);
            if (target != null) {
                BigDecimal allocate = target.outstanding().min(remaining);
                if (allocate.signum() > 0) {
                    ar.insertAllocation(receiptId, target.id(), allocate);
                    ar.addPaidAmount(target.id(), allocate);
                    remaining = remaining.subtract(allocate);
                }
            }
        }
        for (ArInvoice invoice : ar.openInvoices(scope.organizationId())) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (invoice.partyId() != partyId) {
                continue;
            }
            BigDecimal outstanding = invoice.outstanding();
            BigDecimal allocate = outstanding.min(remaining);
            ar.insertAllocation(receiptId, invoice.id(), allocate);
            ar.addPaidAmount(invoice.id(), allocate);
            remaining = remaining.subtract(allocate);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AR_RECEIPT", "ar_receipt",
                String.valueOf(receiptId), "{\"number\":\"" + receiptNumber + "\"}", null));
        return new ArReceipt(receiptId, scope.organizationId(), siteId, receiptNumber, partyId, receiptDate,
                amount, currency == null ? "IDR" : currency, method == null ? "TRANSFER" : method,
                bankAccountId, journal.id(), reference, actor);
    }

    public PageResult<ArInvoice> searchInvoices(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = ar.countInvoices(scope.organizationId(), status);
        List<ArInvoice> items = ar.listInvoices(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public ArInvoice invoiceById(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return ar.findInvoice(scope.organizationId(), id);
    }

    /** Ageing of the open invoices: current, 1-30, 31-60, 61-90, 90+ days overdue. */
    public AgingReport aging(SiteScope scope, LocalDate asOf) {
        if (scope.isDenyAll()) {
            return new AgingReport(List.of());
        }
        List<AgingRow> rows = new ArrayList<>();
        for (ArInvoice invoice : ar.openInvoices(scope.organizationId())) {
            long days = asOf == null ? 0 : asOf.toEpochDay() - invoice.dueDate().toEpochDay();
            rows.add(new AgingRow(invoice.partyId(), invoice.invoiceNumber(), invoice.total(),
                    invoice.paidAmount(), Math.max(0, days)));
        }
        return new AgingReport(rows);
    }

    /** One line of the ageing report. */
    public record AgingRow(long partyId, String invoiceNumber, BigDecimal total, BigDecimal paid, long daysOverdue) {
        public BigDecimal outstanding() {
            return total.subtract(paid);
        }
    }

    public record AgingReport(List<AgingRow> rows) {
        public BigDecimal totalOutstanding() {
            BigDecimal sum = BigDecimal.ZERO;
            for (AgingRow row : rows) {
                sum = sum.add(row.outstanding());
            }
            return sum;
        }
    }

    private Account requireAccount(SiteScope scope, String code, String name) {
        Account account = accounts.byCode(scope, code);
        if (account == null) {
            throw new OrderException("Chart of accounts is missing account " + code + " (" + name + ")");
        }
        return account;
    }

    private long bankAccountGl(SiteScope scope, long bankAccountId) {
        for (BankAccount bank : banks.accounts(scope.organizationId())) {
            if (bank.id() == bankAccountId) {
                return bank.glAccountId();
            }
        }
        throw new OrderException("Unknown bank account: " + bankAccountId);
    }
}
