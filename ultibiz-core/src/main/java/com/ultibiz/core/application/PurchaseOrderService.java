package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.PurchaseOrder;
import com.ultibiz.core.domain.PurchaseOrderLine;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
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
 * Supplier orders: creation, confirmation and receipt (which books the stock
 * in at the receipt cost and updates the moving average).
 */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final PartyRepository parties;
    private final TaxRuleRepository taxRules;
    private final TaxEngine taxEngine;
    private final NumberingService numbering;
    private final GoodsReceiptService receipts;
    private final AuditService audit;

    public PurchaseOrderService(PurchaseOrderRepository orders, PartyRepository parties,
                                TaxRuleRepository taxRules, TaxEngine taxEngine, NumberingService numbering,
                                GoodsReceiptService receipts, AuditService audit) {
        this.orders = orders;
        this.parties = parties;
        this.taxRules = taxRules;
        this.taxEngine = taxEngine;
        this.numbering = numbering;
        this.receipts = receipts;
        this.audit = audit;
    }

    public PurchaseOrder create(SiteScope scope, String actor, String countryCode, long partyId,
                                LocalDate orderDate, LocalDate expectedDate, Long warehouseId, String currency,
                                BigDecimal exchangeRate, String notes, List<PurchaseOrderLineDraft> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new OrderException("A purchase order needs at least one line");
        }
        Party party = parties.findById(scope, partyId).orElse(null);
        if (party == null) {
            throw new OrderException("Unknown supplier: " + partyId);
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        List<BigDecimal> nets = new ArrayList<>();
        for (PurchaseOrderLineDraft draft : lines) {
            BigDecimal net = draft.quantity().multiply(draft.unitPrice())
                    .setScale(2, RoundingMode.HALF_UP);
            nets.add(net);
            subtotal = subtotal.add(net);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxTotal = BigDecimal.ZERO;
        String country = countryCode == null ? "ID" : countryCode.toUpperCase();
        TaxPackContext context = new TaxPackContext(scope.organizationId(), country,
                currency == null ? "IDR" : currency, orderDate, false, RoundingMode.HALF_UP,
                TaxPackContext.RoundingLevel.LINE, 2);
        List<TaxPack.TaxableAmount> taxable = new ArrayList<>();
        for (BigDecimal net : nets) {
            taxable.add(new TaxPack.TaxableAmount(null, net, net, "purchase"));
        }
        List<TaxLine> taxLines = taxEngine.compute(scope.organizationId(), country, taxable, context);
        for (TaxLine taxLine : taxLines) {
            taxTotal = taxTotal.add(taxLine.taxAmount());
        }
        taxTotal = taxTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(taxTotal).setScale(2, RoundingMode.HALF_UP);

        String orderNumber = numbering.next("PO", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long orderId = orders.insertHeader(scope.organizationId(), siteId, country, orderNumber, partyId,
                orderDate, expectedDate, warehouseId, currency == null ? "IDR" : currency,
                exchangeRate == null ? BigDecimal.ONE : exchangeRate, subtotal, taxTotal, total, notes, actor);

        int lineNo = 1;
        for (int i = 0; i < lines.size(); i++) {
            PurchaseOrderLineDraft draft = lines.get(i);
            BigDecimal net = nets.get(i);
            BigDecimal tax = i < taxLines.size() ? taxLines.get(i).taxAmount() : BigDecimal.ZERO;
            orders.insertLine(orderId, lineNo++, draft.productId(), "Product " + draft.productId(),
                    draft.quantity(), draft.unitPrice(), null, null, net, tax,
                    net.add(tax).setScale(2, RoundingMode.HALF_UP));
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PURCHASE_ORDER_CREATE", "purchase_order",
                String.valueOf(orderId), "{\"number\":\"" + orderNumber + "\"}", null));
        return orders.findById(scope.organizationId(), orderId);
    }

    public PurchaseOrder confirm(SiteScope scope, String actor, long orderId) {
        PurchaseOrder order = orders.findById(scope.organizationId(), orderId);
        if (order == null) {
            throw new OrderException("Purchase order not found: " + orderId);
        }
        if (!"DRAFT".equals(order.status())) {
            throw new OrderException("Only a draft order can be confirmed (status: " + order.status() + ")");
        }
        orders.updateStatus(orderId, "CONFIRMED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PURCHASE_ORDER_CONFIRM", "purchase_order",
                String.valueOf(orderId), null, null));
        return orders.findById(scope.organizationId(), orderId);
    }

    /** Books the received goods into the stock at the receipt cost. */
    public PurchaseOrder receive(SiteScope scope, String actor, long orderId, List<ReceiptLine> receiptLines) {
        PurchaseOrder order = orders.findById(scope.organizationId(), orderId);
        if (order == null) {
            throw new OrderException("Purchase order not found: " + orderId);
        }
        if (!"CONFIRMED".equals(order.status()) && !"PARTIALLY_RECEIVED".equals(order.status())) {
            throw new OrderException("Only a confirmed order can be received (status: " + order.status() + ")");
        }
        if (order.warehouseId() == null) {
            throw new OrderException("The order has no warehouse, so nothing can be received");
        }
        List<PurchaseOrderLine> orderLines = orders.lines(orderId);
        List<GoodsReceiptService.ReceiptMovement> movements = new ArrayList<>();
        for (ReceiptLine requested : receiptLines) {
            PurchaseOrderLine line = orderLines.stream()
                    .filter(l -> l.id() == requested.lineId())
                    .findFirst()
                    .orElseThrow(() -> new OrderException("Unknown order line: " + requested.lineId()));
            BigDecimal remaining = line.quantity().subtract(line.quantityReceived());
            if (requested.quantity().signum() <= 0 || requested.quantity().compareTo(remaining) > 0) {
                throw new OrderException("Cannot receive " + requested.quantity().toPlainString()
                        + " of line " + line.lineNo() + "; remaining: " + remaining.toPlainString());
            }
            movements.add(new GoodsReceiptService.ReceiptMovement(line.productId(), null,
                    requested.quantity(), requested.unitCost()));
            orders.addReceivedQuantity(line.id(), requested.quantity());
        }

        receipts.post(scope, actor, "PURCHASE", order.orderNumber(), orderId, null, null, order.partyId(),
                order.warehouseId(), order.orderDate(), order.currency(), movements,
                "Receipt for " + order.orderNumber());

        boolean complete = true;
        for (PurchaseOrderLine line : orders.lines(orderId)) {
            if (line.quantityReceived().compareTo(line.quantity()) < 0) {
                complete = false;
                break;
            }
        }
        orders.updateStatus(orderId, complete ? "RECEIVED" : "PARTIALLY_RECEIVED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PURCHASE_ORDER_RECEIVE", "purchase_order",
                String.valueOf(orderId), null, null));
        return orders.findById(scope.organizationId(), orderId);
    }

    public PageResult<PurchaseOrder> search(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = orders.count(scope.organizationId(), status);
        List<PurchaseOrder> items = orders.list(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public PurchaseOrder byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return orders.findById(scope.organizationId(), id);
    }

    public List<PurchaseOrderLine> lines(SiteScope scope, long orderId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return orders.lines(orderId);
    }
}
