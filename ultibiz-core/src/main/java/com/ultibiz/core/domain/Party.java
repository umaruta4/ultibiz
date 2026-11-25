package com.ultibiz.core.domain;

import java.math.BigDecimal;

/**
 * A business partner: customer, supplier or both. {@code siteId == null} means
 * the partner is shared across every site of the organization.
 */
public record Party(
        long id,
        long organizationId,
        Long siteId,
        String code,
        String name,
        String partyType,
        String taxId,
        String countryCode,
        String email,
        String phone,
        BigDecimal creditLimit,
        int paymentTermDays,
        String currency,
        boolean active,
        long rowVersion) {
}
