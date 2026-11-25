package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One immutable stock movement with the balance it produced. */
public record StockMovement(
        long id,
        long organizationId,
        long siteId,
        long warehouseId,
        Long locationId,
        long productId,
        LocalDate movementDate,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        BigDecimal balanceAfter,
        String sourceType,
        Long sourceId,
        Integer sourceLine,
        String createdBy) {
}
