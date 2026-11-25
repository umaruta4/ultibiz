package com.ultibiz.spi.tax;

import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The rules a tax pack needs for one document.
 *
 * @param organizationId the tenant the document belongs to
 * @param taxInclusive   true when the entered amounts already contain the tax
 * @param roundingMode   rounding mode the country requires
 * @param roundingLevel  LINE = round every line, DOCUMENT = round the total
 * @param scale          number of decimals for the tax amount
 */
public record TaxPackContext(
        long organizationId,
        String countryCode,
        String currency,
        LocalDate onDate,
        boolean taxInclusive,
        RoundingMode roundingMode,
        RoundingLevel roundingLevel,
        int scale) {

    public enum RoundingLevel {
        LINE,
        DOCUMENT
    }
}
