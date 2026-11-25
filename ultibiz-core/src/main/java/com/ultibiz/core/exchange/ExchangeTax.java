package com.ultibiz.core.exchange;

import java.math.BigDecimal;

/** One tax breakdown entry of an exchanged document (per tax code + rate). */
public record ExchangeTax(
        String taxCode,
        BigDecimal percent,
        BigDecimal taxableAmount,
        BigDecimal taxAmount) {
}
