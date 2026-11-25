package com.ultibiz.core.exchange;

/** A parse or render failure of an exchange adapter. */
public class ExchangeException extends RuntimeException {

    public ExchangeException(String message) {
        super(message);
    }

    public ExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
