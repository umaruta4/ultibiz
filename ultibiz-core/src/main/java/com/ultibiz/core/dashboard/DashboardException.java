package com.ultibiz.core.dashboard;

/** A broken dashboard rule: the message is safe to show to the user. */
public class DashboardException extends RuntimeException {
    public DashboardException(String message) {
        super(message);
    }
}
