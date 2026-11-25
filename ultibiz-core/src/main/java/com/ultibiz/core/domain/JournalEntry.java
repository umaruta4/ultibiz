package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A posted journal header. A journal is immutable once posted; corrections are
 * made by posting a reversal ({@link #reversesJournalId()}).
 */
public record JournalEntry(
        long id,
        long organizationId,
        Long siteId,
        String countryCode,
        String journalNumber,
        String journalType,
        LocalDate postingDate,
        long fiscalPeriodId,
        String currency,
        BigDecimal exchangeRate,
        String reference,
        String sourceType,
        Long sourceId,
        Long reversesJournalId,
        String description,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        String status,
        String postedBy,
        Instant postedAt) {

    public boolean balanced() {
        return totalDebit != null && totalCredit != null
                && totalDebit.compareTo(totalCredit) == 0;
    }
}
