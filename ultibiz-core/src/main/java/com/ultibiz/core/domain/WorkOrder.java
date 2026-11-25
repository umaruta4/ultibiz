package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A production order. */
public record WorkOrder(
        long id,
        long organizationId,
        long siteId,
        String orderNumber,
        long productId,
        Long bomId,
        Long productionLineId,
        long warehouseId,
        BigDecimal quantity,
        BigDecimal quantityProduced,
        String status,
        LocalDate plannedStart,
        LocalDate plannedEnd,
        LocalDate actualStart,
        LocalDate actualEnd,
        BigDecimal materialCost,
        String createdBy,
        long rowVersion) {
}
