package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A matching tolerance (three-way match, receipt, payment). */
public record MatchRule(
        long id,
        long organizationId,
        String ruleType,
        BigDecimal tolerancePercent,
        BigDecimal toleranceAmount,
        boolean active) {
}
