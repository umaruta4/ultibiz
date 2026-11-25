package com.ultibiz.core.domain;

/**
 * A chart-of-accounts line. {@code countryCode == null} means the account is
 * usable in every country of the organization.
 */
public record Account(
        long id,
        long organizationId,
        String countryCode,
        String code,
        String name,
        AccountType accountType,
        Long parentId,
        NormalBalance normalBalance,
        String currency,
        boolean postable,
        boolean active,
        long rowVersion) {
}
