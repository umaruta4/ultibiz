package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** One monthly depreciation run of an asset. */
public record AssetDepreciation(
        long id,
        long organizationId,
        long assetId,
        int periodYear,
        int periodMonth,
        String method,
        BigDecimal depreciationAmount,
        BigDecimal accumulatedAmount,
        BigDecimal bookValueAfter,
        Long journalId) {
}
