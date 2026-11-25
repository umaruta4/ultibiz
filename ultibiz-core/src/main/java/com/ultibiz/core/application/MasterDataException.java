package com.ultibiz.core.application;

/**
 * Raised when master data violates a rule (duplicate code, missing field,
 * optimistic-lock conflict). The web layer turns it into a 400 with the
 * message; nothing is written in that case.
 */
public class MasterDataException extends RuntimeException {

    public MasterDataException(String message) {
        super(message);
    }
}
