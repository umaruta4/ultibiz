package com.ultibiz.core.hrm;

/** A broken HRM rule: the message is safe to show to the user. */
public class HrmException extends RuntimeException {
    public HrmException(String message) {
        super(message);
    }
}
