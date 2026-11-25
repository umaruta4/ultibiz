package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A line of a supplier bill. */
public record ApBillLine(
        long id,
        long billId,
        int lineNo,
        Long productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Long taxCategoryId,
        BigDecimal taxRate,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount) {
}
