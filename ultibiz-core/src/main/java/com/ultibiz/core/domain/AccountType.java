package com.ultibiz.core.domain;

/** The five classical account families. */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE;

    /** True for the families that increase with a debit. */
    public boolean debitNormal() {
        return this == ASSET || this == EXPENSE;
    }
}
