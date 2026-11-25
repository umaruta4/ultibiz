package com.ultibiz.core.application;

import java.math.BigDecimal;

/** How much of an order line is shipped in this delivery. */
public record ShipmentLine(long lineId, BigDecimal quantity) {
}
