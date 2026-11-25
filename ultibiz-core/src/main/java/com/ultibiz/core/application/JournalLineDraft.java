package com.ultibiz.core.application;

import java.math.BigDecimal;

/**
 * A line to post. Exactly one of {@code debit}/{@code credit} may be
 * non-zero; the posting engine rejects lines with both or with neither.
 */
public record JournalLineDraft(
        long accountId,
        Long partyId,
        Long siteId,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        Long taxCategoryId,
        BigDecimal taxRate) {

    public BigDecimal debitOrZero() {
        return debit == null ? BigDecimal.ZERO : debit;
    }

    public BigDecimal creditOrZero() {
        return credit == null ? BigDecimal.ZERO : credit;
    }
}
