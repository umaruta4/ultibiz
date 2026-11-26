package com.ultibiz.core.ai;

/** An AI provider failure that the assistant turns into a readable message. */
public class AiException extends RuntimeException {

    private final boolean retryable;

    public AiException(String message) {
        this(message, true, null);
    }

    public AiException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
