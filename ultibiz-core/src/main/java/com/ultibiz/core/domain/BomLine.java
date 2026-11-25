package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A component of a bill of material. */
public record BomLine(
        long id,
        long bomId,
        int lineNo,
        long componentProductId,
        BigDecimal quantity,
        BigDecimal scrapPercent,
        Long parentLineId,
        boolean phantom) {
}
