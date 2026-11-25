package com.ultibiz.core.application;

/**
 * Raised when no usable numbering sequence exists for a document type, or the
 * sequence could not be advanced.
 */
public class NumberingException extends RuntimeException {

    public NumberingException(String message) {
        super(message);
    }
}
