package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money received from a customer. */
public record ArReceipt(
        long id,
        long organizationId,
        long siteId,
        String receiptNumber,
        long partyId,
        LocalDate receiptDate,
        BigDecimal amount,
        String currency,
        String method,
        Long bankAccountId,
        long journalId,
        String reference,
        String createdBy) {
}
