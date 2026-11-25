package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A line of a customer invoice. */
public record ArInvoiceLine(
        long id,
        long invoiceId,
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
        BigDecimal totalAmount) {
}
