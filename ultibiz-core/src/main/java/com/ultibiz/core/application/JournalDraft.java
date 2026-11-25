package com.ultibiz.core.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A journal to be posted: header fields plus its lines. The posting engine
 * refuses to write anything that does not balance.
 */
public record JournalDraft(
        String countryCode,
        Long siteId,
        String journalType,
        LocalDate postingDate,
        String currency,
        BigDecimal exchangeRate,
        String reference,
        String sourceType,
        Long sourceId,
        Long reversesJournalId,
        String description,
        java.util.List<JournalLineDraft> lines) {
}
