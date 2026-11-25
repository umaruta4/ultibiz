package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A line of a customer order. */
public record SalesOrderLine(
        long id,
        long orderId,
        int lineNo,
        Long productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discount,
        Long taxCategoryId,
        BigDecimal taxRate,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        BigDecimal quantityShipped,
        BigDecimal quantityInvoiced) {
}
