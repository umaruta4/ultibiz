package com.ultibiz.core.crm;

/** A broken CRM rule: the message is safe to show to the user. */
public class CrmException extends RuntimeException {
    public CrmException(String message) {
        super(message);
    }
}
