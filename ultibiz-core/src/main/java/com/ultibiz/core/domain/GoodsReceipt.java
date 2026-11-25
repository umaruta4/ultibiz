package com.ultibiz.core.domain;

import java.time.LocalDate;

/** A goods movement: receipt, issue, delivery or production output. */
public record GoodsReceipt(
        long id,
        long organizationId,
        long siteId,
        String receiptNumber,
        String sourceType,
        Long purchaseOrderId,
        Long salesOrderId,
        Long workOrderId,
        Long partyId,
        long warehouseId,
        LocalDate receiptDate,
        String currency,
        String status,
        String notes,
        String createdBy) {
}
