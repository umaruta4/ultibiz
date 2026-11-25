package com.ultibiz.core.application;

import com.ultibiz.core.domain.Account;
import com.ultibiz.core.domain.ApBill;
import com.ultibiz.core.domain.ApBillLine;
import com.ultibiz.core.domain.ApPayment;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.BankAccount;
import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.domain.MatchRule;
import com.ultibiz.core.domain.PurchaseOrder;
import com.ultibiz.core.domain.PurchaseOrderLine;
import com.ultibiz.core.infrastructure.ApRepository;
import com.ultibiz.core.infrastructure.BankRepository;
import com.ultibiz.core.infrastructure.MatchRuleRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Accounts payable: issue a bill, record a payment, allocate it against open
 * bills, and run a three-way match (order, receipt, bill).
 */
@Service
public class ApService {

    private final ApRepository ap;
    private final AccountService accounts;
    private final JournalService journals;
    private final PurchaseOrderRepository purchaseOrders;
    private final GoodsReceiptRepository goodsReceipts;
    private final MatchRuleRepository matchRules;
    private final NumberingService numbering;
    private final BankRepository banks;
    private final AuditService audit;

    public ApService(ApRepository ap, AccountService accounts, JournalService journals,
                     PurchaseOrderRepository purchaseOrders, GoodsReceiptRepository goodsReceipts,
                     MatchRuleRepository matchRules, NumberingService numbering, BankRepository banks,
                     AuditService audit) {
        this.ap = ap;
        this.accounts = accounts;
        this.journals = journals;
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts = goodsReceipts;
        this.matchRules = matchRules;
        this.numbering = numbering;
        this.banks = banks;
        this.audit = audit;
    }

    public ApBill issueBill(SiteScope scope, String actor, String countryCode, long partyId,
                             Long purchaseOrderId, LocalDate billDate, LocalDate dueDate, String currency,
                             List<PurchaseOrderLineDraft> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new OrderException("A bill needs at least one line");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchaseOrderLineDraft line : lines) {
            subtotal = subtotal.add(line.quantity().multiply(line.unitPrice())
                    .setScale(2, RoundingMode.HALF_UP));
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal total = subtotal.add(taxTotal).setScale(2, RoundingMode.HALF_UP);

        Account inventory = requireAccount(scope, "1300", "Inventory");
        Account payable = requireAccount(scope, "2100", "Accounts Payable");

        List<JournalLineDraft> journalLines = new ArrayList<>();
        journalLines.add(new JournalLineDraft(inventory.id(), null, null, "Purchase of goods",
                subtotal, BigDecimal.ZERO, null, null));
        journalLines.add(new JournalLineDraft(payable.id(), partyId, null, "Supplier bill",
                BigDecimal.ZERO, total, null, null));

        JournalEntry journal = journals.post(scope, actor, new JournalDraft(countryCode, null, "PURCHASE",
                billDate, currency == null ? "IDR" : currency, BigDecimal.ONE, null, "ap_bill", null, null,
                "Supplier bill", journalLines));

        String billNumber = numbering.next("BILL", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long billId = ap.insertBill(scope.organizationId(), siteId, countryCode == null ? "ID" : countryCode,
                billNumber, partyId, purchaseOrderId, billDate, dueDate,
                currency == null ? "IDR" : currency, subtotal, taxTotal, total, journal.id(), actor);

        int lineNo = 1;
        for (PurchaseOrderLineDraft line : lines) {
            BigDecimal net = line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            ap.insertBillLine(billId, lineNo++, line.productId(),
                    line.description() == null ? "" : line.description(), line.quantity(), line.unitPrice(),
                    line.taxCategoryId(), null, net, BigDecimal.ZERO, net);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AP_BILL_ISSUE", "ap_bill",
                String.valueOf(billId), "{\"number\":\"" + billNumber + "\"}", null));
        return ap.findBill(scope.organizationId(), billId);
    }

    /** Records money paid and allocates it against the open bills, oldest due first. */
    public ApPayment recordPayment(SiteScope scope, String actor, long partyId, LocalDate paymentDate,
                                    BigDecimal amount, String currency, String method, Long bankAccountId,
                                    String reference) {
        return recordPayment(scope, actor, partyId, paymentDate, amount, currency, method, bankAccountId,
                reference, null);
    }

    /**
     * Records money paid. With {@code targetBillId} the amount is allocated to
     * that bill first; the remainder goes to the oldest open bills of the same
     * party.
     */
    public ApPayment recordPayment(SiteScope scope, String actor, long partyId, LocalDate paymentDate,
                                    BigDecimal amount, String currency, String method, Long bankAccountId,
                                    String reference, Long targetBillId) {
        if (amount == null || amount.signum() <= 0) {
            throw new OrderException("A payment needs a positive amount");
        }
        Account payable = requireAccount(scope, "2100", "Accounts Payable");
        long cashAccountId = bankAccountId != null
                ? bankAccountGl(scope, bankAccountId)
                : requireAccount(scope, "1100", "Cash on Hand").id();

        List<JournalLineDraft> journalLines = new ArrayList<>();
        journalLines.add(new JournalLineDraft(payable.id(), partyId, null, "Payment to " + partyId,
                amount, BigDecimal.ZERO, null, null));
        journalLines.add(new JournalLineDraft(cashAccountId, null, null, "Payment to " + partyId,
                BigDecimal.ZERO, amount, null, null));

        JournalEntry journal = journals.post(scope, actor, new JournalDraft("ID", null, "PAYMENT",
                paymentDate, currency == null ? "IDR" : currency, BigDecimal.ONE, reference,
                "ap_payment", null, null, "Supplier payment", journalLines));

        String paymentNumber = numbering.next("PAY", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long paymentId = ap.insertPayment(scope.organizationId(), siteId, paymentNumber, partyId, paymentDate,
                amount, currency == null ? "IDR" : currency, method == null ? "TRANSFER" : method,
                bankAccountId, journal.id(), reference, actor);

        BigDecimal remaining = amount;
        if (targetBillId != null) {
            ApBill target = ap.findBill(scope.organizationId(), targetBillId);
            if (target != null) {
                BigDecimal allocate = target.outstanding().min(remaining);
                if (allocate.signum() > 0) {
                    ap.insertAllocation(paymentId, target.id(), allocate);
                    ap.addPaidAmount(target.id(), allocate);
                    remaining = remaining.subtract(allocate);
                }
            }
        }
        for (ApBill bill : ap.openBills(scope.organizationId())) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (bill.partyId() != partyId) {
                continue;
            }
            BigDecimal outstanding = bill.outstanding();
            BigDecimal allocate = outstanding.min(remaining);
            ap.insertAllocation(paymentId, bill.id(), allocate);
            ap.addPaidAmount(bill.id(), allocate);
            remaining = remaining.subtract(allocate);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "AP_PAYMENT", "ap_payment",
                String.valueOf(paymentId), "{\"number\":\"" + paymentNumber + "\"}", null));
        return new ApPayment(paymentId, scope.organizationId(), siteId, paymentNumber, partyId, paymentDate,
                amount, currency == null ? "IDR" : currency, method == null ? "TRANSFER" : method,
                bankAccountId, journal.id(), reference, actor);
    }

    /**
     * Three-way match: the billed quantity must not exceed the received
     * quantity by more than the tolerance.
     */
    public MatchResult threeWayMatch(SiteScope scope, long billId) {
        ApBill bill = ap.findBill(scope.organizationId(), billId);
        if (bill == null) {
            throw new OrderException("Bill not found: " + billId);
        }
        if (bill.purchaseOrderId() == null) {
            return new MatchResult(true, List.of(), "no purchase order linked");
        }
        MatchRule rule = matchRules.find(scope.organizationId(), "THREE_WAY_MATCH");
        BigDecimal tolerancePercent = rule == null ? BigDecimal.ZERO : rule.tolerancePercent();
        BigDecimal toleranceAmount = rule == null ? BigDecimal.ZERO : rule.toleranceAmount();

        List<ApBillLine> billLines = ap.billLines(billId);
        List<MatchVariance> variances = new ArrayList<>();
        boolean matched = true;

        // received quantity per product for this order
        java.util.Map<Long, BigDecimal> received = new java.util.HashMap<>();
        PurchaseOrder order = purchaseOrders.findById(scope.organizationId(), bill.purchaseOrderId());
        if (order != null) {
            for (PurchaseOrderLine line : purchaseOrders.lines(order.id())) {
                received.merge(line.productId() == null ? -1L : line.productId(),
                        line.quantityReceived(), BigDecimal::add);
            }
        }

        for (ApBillLine line : billLines) {
            Long productId = line.productId() == null ? -1L : line.productId();
            BigDecimal billed = line.quantity();
            BigDecimal receivedQty = received.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal variance = billed.subtract(receivedQty);
            if (variance.signum() <= 0) {
                continue;
            }
            BigDecimal limit = toleranceAmount.max(
                    receivedQty.multiply(tolerancePercent));
            if (variance.compareTo(limit) > 0) {
                matched = false;
                variances.add(new MatchVariance(line.lineNo(), productId, billed, receivedQty, variance));
            }
        }
        return new MatchResult(matched, variances, toleranceAmount.toPlainString());
    }

    public PageResult<ApBill> searchBills(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = ap.countBills(scope.organizationId(), status);
        List<ApBill> items = ap.listBills(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public ApBill billById(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return ap.findBill(scope.organizationId(), id);
    }

    /** Ageing of the open bills. */
    public ArService.AgingReport aging(SiteScope scope, LocalDate asOf) {
        if (scope.isDenyAll()) {
            return new ArService.AgingReport(List.of());
        }
        List<ArService.AgingRow> rows = new ArrayList<>();
        for (ApBill bill : ap.openBills(scope.organizationId())) {
            long days = asOf == null ? 0 : asOf.toEpochDay() - bill.dueDate().toEpochDay();
            rows.add(new ArService.AgingRow(bill.partyId(), bill.billNumber(), bill.total(),
                    bill.paidAmount(), Math.max(0, days)));
        }
        return new ArService.AgingReport(rows);
    }

    /** One variance found by the three-way match. */
    public record MatchVariance(int lineNo, long productId, BigDecimal billed, BigDecimal received,
                                 BigDecimal variance) {
    }

    /** The result of a three-way match. */
    public record MatchResult(boolean matched, List<MatchVariance> variances, String toleranceApplied) {
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
