package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A customer invoice (AR). */
public record ArInvoice(
        long id,
        long organizationId,
        long siteId,
        String countryCode,
        String invoiceNumber,
        long partyId,
        Long salesOrderId,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String currency,
        BigDecimal exchangeRate,
        boolean taxInclusive,
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
