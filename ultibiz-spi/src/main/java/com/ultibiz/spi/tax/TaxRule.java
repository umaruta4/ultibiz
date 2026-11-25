package com.ultibiz.spi.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A tax rule as seen by the tax SPI. Rules are effective-dated: a rule applies
 * on {@code onDate} when {@code effectiveFrom <= onDate} and
 * {@code effectiveTo} is null or {@code >= onDate}.
 *
 * @param categoryCode tax category (PPN_STANDARD, WITHHOLDING, ...)
 * @param countryCode  ISO-3166 alpha-2 country the rule belongs to
 * @param rate         rate as a fraction (0.11 = 11%), never a float
 */
public record TaxRule(
        Long id,
        String categoryCode,
        String countryCode,
        String name,
        BigDecimal rate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int priority) {

    public boolean appliesOn(LocalDate date) {
        if (date == null) {
            return false;
        }
        if (effectiveFrom != null && date.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || !date.isAfter(effectiveTo);
    }
}
