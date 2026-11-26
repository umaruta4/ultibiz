package com.ultibiz.core.sso;

/** A failure of the single sign-on. */
public class SsoException extends RuntimeException {

    public SsoException(String message) {
        super(message);
    }
}
