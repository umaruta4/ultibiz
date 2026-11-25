package com.ultibiz.core.exchange;

import java.math.BigDecimal;

/** One line of an exchanged document. */
public record ExchangeLine(
        String productCode,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String taxCode,
        BigDecimal taxPercent) {

    /** The net amount of the line (quantity * unit price). */
    public BigDecimal netAmount() {
        return quantity.multiply(unitPrice);
    }

    /** The tax of the line. */
    public BigDecimal taxAmount() {
        return netAmount().multiply(taxPercent)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }
}
