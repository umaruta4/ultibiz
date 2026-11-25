package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A receipt amount allocated to an invoice. */
public record ArAllocation(long id, long receiptId, long invoiceId, BigDecimal amount) {
}
