package com.ultibiz.spi.tax;

import java.math.BigDecimal;

/**
 * A computed tax line. It carries the rule that was applied so the document
 * stays reproducible after a later rate change.
 *
 * @param baseAmount  taxable base
 * @param taxAmount   computed tax (base * rate, rounded half-up)
 * @param rule        the rule snapshot used, may be null for non-taxable lines
 */
public record TaxLine(
        String categoryCode,
        String countryCode,
        String name,
        BigDecimal rate,
        BigDecimal baseAmount,
        BigDecimal taxAmount,
        TaxRule rule) {
}
