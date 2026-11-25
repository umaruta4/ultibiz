package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One imported statement line of a bank account. */
public record BankStatementLine(
        long id,
        long bankAccountId,
        String importBatch,
        LocalDate statementDate,
        String description,
        String reference,
        BigDecimal amount,
        String currency,
        boolean reconciled,
        Long matchedMovementId) {
}
