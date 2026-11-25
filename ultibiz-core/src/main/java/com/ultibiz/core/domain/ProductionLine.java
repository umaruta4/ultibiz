package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A production line, owned by one site, optionally bound to warehouse/work center. */
public record ProductionLine(
        long id,
        long organizationId,
        long siteId,
        String code,
        String name,
        Long warehouseId,
        Long workCenterId,
        BigDecimal capacityPerDay,
        boolean active,
        long rowVersion) {
}
