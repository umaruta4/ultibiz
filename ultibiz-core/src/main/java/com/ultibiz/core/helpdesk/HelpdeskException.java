package com.ultibiz.core.helpdesk;

/** A broken helpdesk rule: the message is safe to show to the user. */
public class HelpdeskException extends RuntimeException {
    public HelpdeskException(String message) {
        super(message);
    }
}
