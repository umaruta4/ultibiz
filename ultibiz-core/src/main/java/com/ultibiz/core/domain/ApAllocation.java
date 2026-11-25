package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A payment amount allocated to a bill. */
public record ApAllocation(long id, long paymentId, long billId, BigDecimal amount) {
}
