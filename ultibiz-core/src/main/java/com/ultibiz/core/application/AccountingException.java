package com.ultibiz.core.application;

/**
 * Raised when a posting violates an accounting rule: an unbalanced journal, a
 * closed period, an unknown account, a missing FX rate. Nothing is written.
 */
public class AccountingException extends RuntimeException {

    public AccountingException(String message) {
        super(message);
    }
}
