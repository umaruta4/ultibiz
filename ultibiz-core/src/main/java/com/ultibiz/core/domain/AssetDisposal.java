package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A disposal of an asset: proceeds versus book value. */
public record AssetDisposal(
        long id,
        long organizationId,
        long assetId,
        LocalDate disposalDate,
        BigDecimal proceeds,
        BigDecimal bookValue,
        BigDecimal gainLoss,
        String reason,
        Long journalId,
        String createdBy) {
}
