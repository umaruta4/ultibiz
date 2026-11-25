package com.ultibiz.core.application;

import java.math.BigDecimal;

/**
 * Writable product fields. {@code siteId == null} creates a product shared by
 * every site of the organization.
 */
public record ProductDraft(
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
        boolean active) {
}
