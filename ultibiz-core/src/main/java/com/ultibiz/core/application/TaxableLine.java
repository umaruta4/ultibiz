package com.ultibiz.core.application;

import java.math.BigDecimal;

/**
 * A line to be taxed: the tax category and the taxable base amount.
 */
public record TaxableLine(String categoryCode, BigDecimal baseAmount) {
}
