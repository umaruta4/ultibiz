package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A customer order. */
public record SalesOrder(
        long id,
        long organizationId,
        long siteId,
        String countryCode,
        String orderNumber,
        long partyId,
        LocalDate orderDate,
        LocalDate deliveryDate,
        Long warehouseId,
        String currency,
        BigDecimal exchangeRate,
        String status,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total,
        String notes,
        String createdBy,
        long rowVersion) {
}
