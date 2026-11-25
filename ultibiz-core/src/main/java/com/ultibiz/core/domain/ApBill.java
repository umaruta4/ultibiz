package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A supplier bill (AP). */
public record ApBill(
        long id,
        long organizationId,
        long siteId,
        String countryCode,
        String billNumber,
        long partyId,
        Long purchaseOrderId,
        LocalDate billDate,
        LocalDate dueDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total,
        BigDecimal paidAmount,
        String status,
        long journalId,
        String createdBy,
        long rowVersion) {
    public BigDecimal outstanding() {
        return total.subtract(paidAmount);
    }
}
