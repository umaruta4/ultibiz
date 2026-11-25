package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A product movement inside a goods receipt. */
public record GoodsReceiptLine(
        long id,
        long receiptId,
        int lineNo,
        long productId,
        Long locationId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost) {
}
