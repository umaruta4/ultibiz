package com.ultibiz.core.application;

import java.math.BigDecimal;

/**
 * Writable party fields. {@code siteId == null} creates a partner shared by
 * every site of the organization.
 */
public record PartyDraft(
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
        boolean active) {
}
