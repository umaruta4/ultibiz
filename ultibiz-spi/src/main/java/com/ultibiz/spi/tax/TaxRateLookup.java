package com.ultibiz.spi.tax;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Looks up the tax rule that applies to a category on a date. A tax pack uses
 * it to resolve rates without knowing where they are stored.
 */
@FunctionalInterface
public interface TaxRateLookup {

    Optional<TaxRule> rule(long organizationId, String categoryCode, LocalDate onDate);
}
