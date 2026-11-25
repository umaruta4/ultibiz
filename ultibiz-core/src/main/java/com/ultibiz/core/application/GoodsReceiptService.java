package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.GoodsReceipt;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Goods receipts and issues: the document header plus the stock movements it
 * causes. Every movement is posted through {@link StockService}, so the stock
 * ledger and the receipt stay in step.
 */
@Service
public class GoodsReceiptService {

    /** One product movement of a receipt (a negative quantity is an issue). */
    public record ReceiptMovement(Long productId, Long locationId, BigDecimal quantity, BigDecimal unitCost) {
    }

    private final GoodsReceiptRepository receipts;
    private final StockService stock;
    private final NumberingService numbering;
    private final AuditService audit;

    public GoodsReceiptService(GoodsReceiptRepository receipts, StockService stock,
                               NumberingService numbering, AuditService audit) {
        this.receipts = receipts;
        this.stock = stock;
        this.numbering = numbering;
        this.audit = audit;
    }

    public GoodsReceipt post(SiteScope scope, String actor, String sourceType, String documentNumber,
                             Long purchaseOrderId, Long salesOrderId, Long workOrderId, Long partyId,
                             long warehouseId, LocalDate date, String currency,
                             List<ReceiptMovement> movements, String notes) {
        String receiptNumber = documentNumber != null && !documentNumber.isBlank()
                ? documentNumber : numbering.next("GRN", scope);
        long receiptId = receipts.insertHeader(scope.organizationId(), siteId(scope), receiptNumber, sourceType,
                purchaseOrderId, salesOrderId, workOrderId, partyId, warehouseId, date,
                currency == null ? "IDR" : currency, notes, actor);
        int lineNo = 1;
        for (ReceiptMovement movement : movements) {
            BigDecimal quantity = movement.quantity();
            BigDecimal unitCost = movement.unitCost() == null ? BigDecimal.ZERO : movement.unitCost();
            BigDecimal totalCost = quantity.multiply(unitCost).setScale(2, java.math.RoundingMode.HALF_UP);
            receipts.insertLine(receiptId, lineNo++, movement.productId(), movement.locationId(), quantity,
                    unitCost, totalCost);
            stock.post(scope, actor, siteId(scope), warehouseId, movement.locationId(), movement.productId(),
                    quantity, unitCost, date, sourceType, receiptId, lineNo - 1);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "GOODS_RECEIPT_POST", "goods_receipt",
                String.valueOf(receiptId), "{\"number\":\"" + receiptNumber + "\"}", null));
        return receipts.findById(scope.organizationId(), receiptId);
    }

    public PageResult<GoodsReceipt> search(SiteScope scope, String sourceType, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        List<GoodsReceipt> items = receipts.list(scope.organizationId(), sourceType, safeSize, safePage * safeSize);
        long total = items.size() < safeSize ? safePage * safeSize + items.size() : (safePage + 1) * safeSize;
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public GoodsReceipt byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return receipts.findById(scope.organizationId(), id);
    }

    public List<com.ultibiz.core.domain.GoodsReceiptLine> lines(SiteScope scope, long receiptId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return receipts.lines(receiptId);
    }

    private static long siteId(SiteScope scope) {
        return scope.siteIds().stream().findFirst().orElse(0L);
    }
}
