package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A bill of material: the recipe of a finished product. */
public record Bom(
        long id,
        long organizationId,
        Long siteId,
        long productId,
        String code,
        String name,
        BigDecimal quantity,
        boolean active,
        long rowVersion) {
}
