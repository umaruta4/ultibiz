package com.ultibiz.core.domain;

/**
 * A document-numbering sequence. {@code siteId == null} = organization-wide
 * sequence, otherwise site-scoped.
 */
public record NumberingSequence(
        long id,
        long organizationId,
        Long siteId,
        String documentType,
        String name,
        String prefix,
        String suffix,
        int padding,
        long startAt,
        long counter,
        String periodType,
        int fiscalYearStartMonth,
        String lastPeriodKey) {

    public boolean periodic() {
        return periodType != null && !"NONE".equalsIgnoreCase(periodType);
    }
}
