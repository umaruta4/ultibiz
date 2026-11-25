package com.ultibiz.spi.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * SPI for exchange rates. The core ships a database-backed provider; a plugin
 * may add a live provider (bank feed, central bank API).
 */
public interface FxRateProvider {

    /**
     * @param base  functional currency
     * @param quote transaction currency
     * @param onDate date the rate must be valid for
     * @return the rate (1 quote = rate base), empty when unknown
     */
    Optional<BigDecimal> rate(long organizationId, String base, String quote, LocalDate onDate);

    String providerId();
}
