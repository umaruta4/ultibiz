package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A maintenance job for an asset. */
public record AssetMaintenance(
        long id,
        long organizationId,
        long assetId,
        Long planId,
        LocalDate dueDate,
        LocalDate doneDate,
        String status,
        BigDecimal cost,
        String note,
        String createdBy) {
}
