package com.ultibiz.core.domain;

/** A bank or cash account, linked to a general ledger account. */
public record BankAccount(
        long id,
        long organizationId,
        long siteId,
        String code,
        String name,
        String bankName,
        String accountNumber,
        long glAccountId,
        String currency,
        boolean active) {
}
