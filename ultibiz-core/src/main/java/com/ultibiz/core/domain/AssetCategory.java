package com.ultibiz.core.domain;

import java.math.BigDecimal;

/**
 * A category of assets with its depreciation rule. The three methods are
 * straight line, declining balance and units of production.
 */
public record AssetCategory(
        long id,
        long organizationId,
        String code,
        String name,
        String depreciationMethod,
        int usefulLifeMonths,
        BigDecimal salvagePercent,
        BigDecimal totalProductionUnits,
        Long assetAccountId,
        Long accumulatedDepreciationAccountId,
        Long depreciationExpenseAccountId,
        boolean active) {

    /** The depreciable base (cost minus salvage). */
    public BigDecimal depreciableBase(BigDecimal cost) {
        return cost.subtract(cost.multiply(salvagePercent));
    }
}
