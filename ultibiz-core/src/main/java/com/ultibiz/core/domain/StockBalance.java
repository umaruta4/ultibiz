package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** Current stock of a product in a warehouse (or at one location). */
public record StockBalance(
        long id,
        long organizationId,
        long siteId,
        long warehouseId,
        Long locationId,
        long productId,
        BigDecimal quantity,
        BigDecimal averageCost,
        BigDecimal totalValue) {
}
