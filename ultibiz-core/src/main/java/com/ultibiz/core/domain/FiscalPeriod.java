package com.ultibiz.core.domain;

import java.time.LocalDate;

/**
 * A fiscal month. Periods are country-scoped because the fiscal calendar
 * differs per country (Indonesia: calendar year; some EU countries: not).
 */
public record FiscalPeriod(
        long id,
        long organizationId,
        String countryCode,
        int fiscalYear,
        int fiscalMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        PeriodStatus status,
        java.time.Instant closedAt,
        String closedBy,
        java.time.Instant reopenedAt,
        String reopenedBy) {

    public boolean isOpen() {
        return status == PeriodStatus.OPEN || status == PeriodStatus.REOPENED;
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(periodStart) && !date.isAfter(periodEnd);
    }
}
