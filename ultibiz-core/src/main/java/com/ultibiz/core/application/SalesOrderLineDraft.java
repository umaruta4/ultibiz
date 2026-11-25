package com.ultibiz.core.application;

import java.math.BigDecimal;

/** A line to put on a customer order. */
public record SalesOrderLineDraft(
        Long productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discount,
        Long taxCategoryId) {
}
