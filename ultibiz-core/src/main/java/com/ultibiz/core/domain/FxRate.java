package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An exchange rate: {@code rate} expresses one {@code quoteCurrency} in
 * {@code baseCurrency} (base -> quote).
 */
public record FxRate(
        long id,
        long organizationId,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        LocalDate effectiveDate,
        String source) {
}
