package com.ultibiz.core.application;

import java.math.BigDecimal;

/** How much of a purchase order line is received, and at which cost. */
public record ReceiptLine(long lineId, BigDecimal quantity, BigDecimal unitCost) {
}
