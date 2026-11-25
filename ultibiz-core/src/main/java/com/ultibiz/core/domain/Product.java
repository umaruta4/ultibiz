package com.ultibiz.core.domain;

import java.math.BigDecimal;

/**
 * A master-data product. {@code siteId == null} means the product is shared
 * across every site of the organization.
 */
public record Product(
        long id,
        long organizationId,
        Long siteId,
        String code,
        String name,
        String productType,
        String uomCode,
        String barcode,
        boolean batchTracked,
        boolean serialTracked,
        boolean trackInventory,
        BigDecimal standardCost,
        String currency,
        Long taxCategoryId,
        String description,
        boolean active,
        int leadTimeDays,
        BigDecimal safetyStock,
        BigDecimal minimumOrderQuantity,
        boolean phantom,
        long rowVersion) {
}
