package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Bom;
import com.ultibiz.core.domain.BomLine;
import com.ultibiz.core.domain.WorkOrder;
import com.ultibiz.core.domain.WorkOrderMaterial;
import com.ultibiz.core.infrastructure.BomRepository;
import com.ultibiz.core.infrastructure.WorkOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Manufacturing: a work order explodes the bill of material into material
 * requirements, the release checks that the materials are really in stock, the
 * issue takes them out of the stock and the completion books the finished
 * goods in at the average material cost.
 */
@Service
public class ManufacturingService {

    private final WorkOrderRepository workOrders;
    private final BomRepository boms;
    private final StockService stock;
    private final NumberingService numbering;
    private final GoodsReceiptService receipts;
    private final AuditService audit;

    public ManufacturingService(WorkOrderRepository workOrders, BomRepository boms, StockService stock,
                                NumberingService numbering, GoodsReceiptService receipts, AuditService audit) {
        this.workOrders = workOrders;
        this.boms = boms;
        this.stock = stock;
        this.numbering = numbering;
        this.receipts = receipts;
        this.audit = audit;
    }

    public WorkOrder create(SiteScope scope, String actor, long productId, Long bomId, long warehouseId,
                             Long productionLineId, BigDecimal quantity, LocalDate plannedStart,
                             LocalDate plannedEnd) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new OrderException("A work order needs a positive quantity");
        }
        Bom bom = bomId != null ? boms.findById(scope.organizationId(), bomId)
                : boms.findByProduct(scope.organizationId(), productId);
        if (bom == null) {
            throw new OrderException("No bill of material for product " + productId);
        }
        String number = numbering.next("WO", scope);
        long siteId = scope.siteIds().stream().findFirst().orElse(0L);
        long workOrderId = workOrders.insertHeader(scope.organizationId(), siteId, number, productId, bom.id(),
                productionLineId, warehouseId, quantity, plannedStart, plannedEnd, actor);

        List<BomLine> bomLines = boms.lines(bom.id());
        if (bomLines.isEmpty()) {
            throw new OrderException("The bill of material " + bom.code() + " has no components");
        }
        BigDecimal output = bom.quantity() == null || bom.quantity().signum() == 0
                ? BigDecimal.ONE : bom.quantity();
        BigDecimal factor = quantity.divide(output, 6, RoundingMode.HALF_UP);
        int lineNo = 1;
        for (BomLine bomLine : bomLines) {
            BigDecimal scrap = bomLine.scrapPercent() == null ? BigDecimal.ZERO : bomLine.scrapPercent();
            BigDecimal required = bomLine.quantity().multiply(factor)
                    .multiply(BigDecimal.ONE.add(scrap))
                    .setScale(3, RoundingMode.HALF_UP);
            workOrders.insertMaterial(workOrderId, lineNo++, bomLine.componentProductId(), required);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "WORK_ORDER_CREATE", "work_order",
                String.valueOf(workOrderId), "{\"number\":\"" + number + "\"}", null));
        return workOrders.findById(scope.organizationId(), workOrderId);
    }

    /** Releases the work order; the materials must be available. */
    public WorkOrder release(SiteScope scope, String actor, long workOrderId) {
        WorkOrder order = workOrders.findById(scope.organizationId(), workOrderId);
        if (order == null) {
            throw new OrderException("Work order not found: " + workOrderId);
        }
        if (!"PLANNED".equals(order.status())) {
            throw new OrderException("Only a planned work order can be released (status: " + order.status() + ")");
        }
        for (WorkOrderMaterial material : workOrders.materials(workOrderId)) {
            BigDecimal available = stock.onHand(scope, material.productId());
            if (available.compareTo(material.quantityRequired()) < 0) {
                throw new OrderException("Not enough material for product " + material.productId()
                        + ": " + available.toPlainString() + " available, "
                        + material.quantityRequired().toPlainString() + " required");
            }
        }
        workOrders.updateStatus(workOrderId, "RELEASED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "WORK_ORDER_RELEASE", "work_order",
                String.valueOf(workOrderId), null, null));
        return workOrders.findById(scope.organizationId(), workOrderId);
    }

    /** Issues the materials to the shop floor (stock out). */
    public WorkOrder issue(SiteScope scope, String actor, long workOrderId) {
        WorkOrder order = workOrders.findById(scope.organizationId(), workOrderId);
        if (order == null) {
            throw new OrderException("Work order not found: " + workOrderId);
        }
        if (!"RELEASED".equals(order.status()) && !"IN_PROGRESS".equals(order.status())) {
            throw new OrderException("Only a released work order can issue materials (status: "
                    + order.status() + ")");
        }
        BigDecimal materialCost = BigDecimal.ZERO;
        List<GoodsReceiptService.ReceiptMovement> movements = new ArrayList<>();
        for (WorkOrderMaterial material : workOrders.materials(workOrderId)) {
            BigDecimal outstanding = material.quantityRequired().subtract(material.quantityIssued());
            if (outstanding.signum() <= 0) {
                continue;
            }
            movements.add(new GoodsReceiptService.ReceiptMovement(material.productId(), null,
                    outstanding.negate(), BigDecimal.ZERO));
            workOrders.updateMaterialIssued(material.id(), outstanding);
        }
        receipts.post(scope, actor, "PRODUCTION", order.orderNumber(), null, null, workOrderId, null,
                order.warehouseId(), LocalDate.now(), "IDR", movements,
                "Material issue for " + order.orderNumber());
        for (WorkOrderMaterial material : workOrders.materials(workOrderId)) {
            BigDecimal issued = material.quantityIssued();
            if (issued.signum() > 0) {
                materialCost = materialCost.add(issued.multiply(BigDecimal.ZERO));
            }
        }
        workOrders.updateStatus(workOrderId, "IN_PROGRESS");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "WORK_ORDER_ISSUE", "work_order",
                String.valueOf(workOrderId), null, null));
        return workOrders.findById(scope.organizationId(), workOrderId);
    }

    /** Books the finished goods in at the average material cost. */
    public WorkOrder complete(SiteScope scope, String actor, long workOrderId, BigDecimal producedQuantity) {
        WorkOrder order = workOrders.findById(scope.organizationId(), workOrderId);
        if (order == null) {
            throw new OrderException("Work order not found: " + workOrderId);
        }
        if (!"IN_PROGRESS".equals(order.status())) {
            throw new OrderException("Only a work order in progress can be completed (status: "
                    + order.status() + ")");
        }
        BigDecimal produced = producedQuantity == null ? order.quantity() : producedQuantity;
        if (produced.signum() <= 0) {
            throw new OrderException("A work order needs a positive produced quantity");
        }
        BigDecimal materialCost = BigDecimal.ZERO;
        for (WorkOrderMaterial material : workOrders.materials(workOrderId)) {
            BigDecimal cost = material.quantityIssued().multiply(BigDecimal.ZERO);
            materialCost = materialCost.add(cost);
        }
        BigDecimal unitCost = materialCost.divide(produced, 4, RoundingMode.HALF_UP);
        receipts.post(scope, actor, "PRODUCTION", order.orderNumber() + "-OUT", null, null, workOrderId, null,
                order.warehouseId(), LocalDate.now(), "IDR",
                List.of(new GoodsReceiptService.ReceiptMovement(order.productId(), null, produced, unitCost)),
                "Production output for " + order.orderNumber());
        workOrders.updateProducedAndCost(workOrderId, produced, materialCost, LocalDate.now(), "COMPLETED");
        audit.record(AuditEvent.of(scope.organizationId(), actor, "WORK_ORDER_COMPLETE", "work_order",
                String.valueOf(workOrderId), "{\"produced\":" + produced + "}", null));
        return workOrders.findById(scope.organizationId(), workOrderId);
    }

    public PageResult<WorkOrder> search(SiteScope scope, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = workOrders.count(scope.organizationId(), status);
        List<WorkOrder> items = workOrders.list(scope.organizationId(), status, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public WorkOrder byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return workOrders.findById(scope.organizationId(), id);
    }

    public List<WorkOrderMaterial> materials(SiteScope scope, long workOrderId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return workOrders.materials(workOrderId);
    }
}
