package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A machine / workstation / vendor work center. */
public record WorkCenter(
        long id,
        long organizationId,
        long siteId,
        String code,
        String name,
        String workCenterType,
        BigDecimal capacityHoursPerDay,
        boolean active,
        long rowVersion) {
}
