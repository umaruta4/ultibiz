package com.ultibiz.core.application;

import java.math.BigDecimal;

/** A line of a purchase order. */
public record PurchaseOrderLineDraft(
        Long productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Long taxCategoryId) {
}
