package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.SalesOrder;
import com.ultibiz.core.domain.SalesOrderLine;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.SalesOrderRepository;
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
 * Customer orders: creation with tax, confirmation and delivery (which moves
 * the stock out and writes a goods receipt).
 */
@Service
public class SalesOrderService {

    private final SalesOrderRepository orders;
    private final PartyRepository parties;
    private final TaxRuleRepository taxRules;
    private final TaxEngine taxEngine;
    private final NumberingService numbering;
    private final GoodsReceiptService receipts;
    private final AuditService audit;

    public SalesOrderService(SalesOrderRepository orders, PartyRepository parties, TaxRuleRepository taxRules,
                             TaxEngine taxEngine, NumberingService numbering,
                             GoodsReceiptService receipts, AuditService audit) {
        this.orders = orders;
        this.parties = parties;
        this.taxRules = taxRules;
        this.taxEngine = taxEngine;
        this.numbering = numbering;
        this.receipts = receipts;
        this.audit = audit;
    }

    public SalesOrder create(SiteScope scope, String actor, String countryCode, long partyId,
                             LocalDate orderDate, LocalDate deliveryDate, Long warehouseId, String currency,
                             BigDecimal exchangeRate, String notes, List<SalesOrderLineDraft> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new OrderException("An order needs at least one line");
        }
        Party party = parties.findById(scope, partyId).orElse(null);
        if (party == null) {
            throw new OrderException("Unknown customer: " + partyId);
        }
        if (!party.active()) {
            throw new OrderException("Customer " + party.code() + " is not active");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<BigDecimal> nets = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        for (SalesOrderLineDraft draft : lines) {
            BigDecimal net = netAmount(draft);
            nets.add(net);
            categories.add(categoryCode(draft.taxCategoryId()));
            subtotal = subtotal.add(net);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        List<TaxPack.TaxableAmount> taxable = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            taxable.add(new TaxPack.TaxableAmount(categories.get(i), nets.get(i), nets.get(i),
                    lines.get(i).description()));
        }
        String country = countryCode == null ? "ID" : countryCode.toUpperCase();
        TaxPackContext context = new TaxPackContext(scope.organizationId(), country,
                currency == null ? "IDR" : currency, orderDate, false, RoundingMode.HALF_UP,
                TaxPackContext.RoundingLevel.LINE, 2);
        List<TaxLine> taxLines = taxEngine.compute(scope.organizationId(), country, taxable, context);
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (TaxLine taxLine : taxLines) {
            taxTotal = taxTotal.add(taxLine.taxAmount());
        }
        taxTotal = taxTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(taxTotal).setScale(2, RoundingMode.HALF_UP);

        if (party.creditLimit() != null && party.creditLimit().signum() > 0
                && total.compareTo(party.creditLimit()) > 0) {
            throw new OrderException("Credit limit exceeded for " + party.code() + ": order "
                    + total.toPlainString() + " > limit " + party.creditLimit().toPlainString());
        }

        String orderNumber = numbering.next("SO", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long orderId = orders.insertHeader(scope.organizationId(), siteId, country, orderNumber, partyId,
                orderDate, deliveryDate, warehouseId, currency == null ? "IDR" : currency,
                exchangeRate == null ? BigDecimal.ONE : exchangeRate, subtotal, taxTotal, total, notes, actor);

        int lineNo = 1;
        for (int i = 0; i < lines.size(); i++) {
            SalesOrderLineDraft draft = lines.get(i);
            BigDecimal net = nets.get(i);
            BigDecimal tax = i < taxLines.size() ? taxLines.get(i).taxAmount() : BigDecimal.ZERO;
            BigDecimal lineTotal = net.add(tax).setScale(2, RoundingMode.HALF_UP);
            orders.insertLine(orderId, lineNo++, draft.productId(),
                    draft.description() == null ? "" : draft.description(), draft.quantity(),
                    draft.unitPrice(), draft.discount() == null ? BigDecimal.ZERO : draft.discount(),
                    draft.taxCategoryId(), i < taxLines.size() ? taxLines.get(i).rate() : null,
                    net, tax, lineTotal);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "SALES_ORDER_CREATE", "sales_order",
                String.valueOf(orderId), "{\"number\":\"" + orderNumber + "\",\"total\":" + total + "}", null));
        return orders.findById(scope.organizationId(), orderId);
    }

    public SalesOrder confirm(SiteScope scope, String actor, long orderId) {
        SalesOrder order = orders.findById(scope.organizationId(), orderId);
        if (order == null) {
            throw new OrderException("Order not found: " + orderId);
        }
        if (!"DRAFT".equals(order.status())) {
            throw new OrderException("Only a draft order can be confirmed (status: " + order.status() + ")");
        }
        orders.updateStatus(orderId, "CONFIRMED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "SALES_ORDER_CONFIRM", "sales_order",
                String.valueOf(orderId), null, null));
        return orders.findById(scope.organizationId(), orderId);
    }

    /** Delivers lines of a confirmed order: goods receipt plus stock out. */
    public SalesOrder deliver(SiteScope scope, String actor, long orderId, List<ShipmentLine> shipment) {
        SalesOrder order = orders.findById(scope.organizationId(), orderId);
        if (order == null) {
            throw new OrderException("Order not found: " + orderId);
        }
        if (!"CONFIRMED".equals(order.status()) && !"PARTIALLY_SHIPPED".equals(order.status())) {
            throw new OrderException("Only a confirmed order can be delivered (status: " + order.status() + ")");
        }
        if (order.warehouseId() == null) {
            throw new OrderException("The order has no warehouse, so nothing can be delivered");
        }
        List<SalesOrderLine> orderLines = orders.lines(orderId);
        List<GoodsReceiptService.ReceiptMovement> movements = new ArrayList<>();
        for (ShipmentLine requested : shipment) {
            SalesOrderLine line = orderLines.stream()
                    .filter(l -> l.id() == requested.lineId())
                    .findFirst()
                    .orElseThrow(() -> new OrderException("Unknown order line: " + requested.lineId()));
            BigDecimal remaining = line.quantity().subtract(line.quantityShipped());
            if (requested.quantity().signum() <= 0 || requested.quantity().compareTo(remaining) > 0) {
                throw new OrderException("Cannot deliver " + requested.quantity().toPlainString()
                        + " of line " + line.lineNo() + "; remaining: " + remaining.toPlainString());
            }
            movements.add(new GoodsReceiptService.ReceiptMovement(line.productId(), null,
                    requested.quantity().negate(), BigDecimal.ZERO));
            orders.addShippedQuantity(line.id(), requested.quantity());
        }

        receipts.post(scope, actor, "SALES", order.orderNumber(), null, orderId, null, order.partyId(),
                order.warehouseId(), order.orderDate(), order.currency(), movements,
                "Delivery of " + order.orderNumber());

        boolean complete = true;
        for (SalesOrderLine line : orders.lines(orderId)) {
            if (line.quantityShipped().compareTo(line.quantity()) < 0) {
                complete = false;
                break;
            }
        }
        orders.updateStatus(orderId, complete ? "SHIPPED" : "PARTIALLY_SHIPPED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "SALES_ORDER_DELIVER", "sales_order",
                String.valueOf(orderId), null, null));
        return orders.findById(scope.organizationId(), orderId);
    }

    /** Status change used by the AR flow (SHIPPED -> INVOICED). */
    public void updateStatus(SiteScope scope, String actor, long orderId, String status) {
        orders.updateStatus(orderId, status);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "SALES_ORDER_" + status, "sales_order",
                String.valueOf(orderId), null, null));
    }

    public PageResult<SalesOrder> search(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = orders.count(scope.organizationId(), status);
        List<SalesOrder> items = orders.list(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public SalesOrder byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return orders.findById(scope.organizationId(), id);
    }

    public List<SalesOrderLine> lines(SiteScope scope, long orderId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return orders.lines(orderId);
    }

    /** Net amount of a line: quantity x price, less the discount fraction. */
    public static BigDecimal netAmount(SalesOrderLineDraft draft) {
        BigDecimal quantity = draft.quantity() == null ? BigDecimal.ZERO : draft.quantity();
        BigDecimal price = draft.unitPrice() == null ? BigDecimal.ZERO : draft.unitPrice();
        BigDecimal discount = draft.discount() == null ? BigDecimal.ZERO : draft.discount();
        return quantity.multiply(price).multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String categoryCode(Long taxCategoryId) {
        if (taxCategoryId == null) {
            return null;
        }
        return taxRules.categoryCode(taxCategoryId).orElse(null);
    }
}
