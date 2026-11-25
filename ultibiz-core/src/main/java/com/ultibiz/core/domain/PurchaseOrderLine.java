package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A line of a supplier order. */
public record PurchaseOrderLine(
        long id,
        long orderId,
        int lineNo,
        Long productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Long taxCategoryId,
        BigDecimal taxRate,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal quantityReceived) {
}
