package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money paid to a supplier. */
public record ApPayment(
        long id,
        long organizationId,
        long siteId,
        String paymentNumber,
        long partyId,
        LocalDate paymentDate,
        BigDecimal amount,
        String currency,
        String method,
        Long bankAccountId,
        long journalId,
        String reference,
        String createdBy) {
}
