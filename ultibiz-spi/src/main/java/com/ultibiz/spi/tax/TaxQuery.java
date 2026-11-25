package com.ultibiz.spi.tax;

import java.time.LocalDate;

/**
 * A request for the tax rule that applies to a line.
 *
 * @param countryCode  country of the transaction
 * @param categoryCode tax category of the line
 * @param onDate       date the tax is computed for (document date)
 * @param sale         true for a sales line, false for a purchase line
 */
public record TaxQuery(String countryCode, String categoryCode, LocalDate onDate, boolean sale) {
}
