package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** One line of a journal: an account, a party, and a debit or a credit. */
public record JournalLine(
        long id,
        long journalId,
        int lineNo,
        long accountId,
        Long partyId,
        Long siteId,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        String currency,
        BigDecimal exchangeRate,
        BigDecimal baseDebit,
        BigDecimal baseCredit,
        Long taxCategoryId,
        BigDecimal taxRate) {

    public boolean isDebit() {
        return debit != null && debit.signum() > 0;
    }
}
