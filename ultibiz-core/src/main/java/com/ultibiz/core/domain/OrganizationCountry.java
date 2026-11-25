package com.ultibiz.core.domain;

/**
 * Per-organization country configuration: currency, locale, timezone and the
 * fiscal calendar used by statutory numbering and reporting.
 */
public record OrganizationCountry(
        long organizationId,
        String countryCode,
        boolean enabled,
        String currency,
        String locale,
        String timezone,
        int fiscalYearStartMonth,
        int fiscalYearStartDay) {
}
