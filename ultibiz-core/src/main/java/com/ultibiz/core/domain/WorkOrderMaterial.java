package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A material requirement of a work order. */
public record WorkOrderMaterial(
        long id,
        long workOrderId,
        int lineNo,
        long productId,
        BigDecimal quantityRequired,
        BigDecimal quantityIssued) {
}
