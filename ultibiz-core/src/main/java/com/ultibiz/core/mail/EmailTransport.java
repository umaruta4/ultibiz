package com.ultibiz.core.mail;

/** A way to send an e-mail (an API provider). */
public interface EmailTransport {

    /** The name of the provider (for the logs). */
    String name();

    /** Whether the provider has everything it needs (the API key, ...). */
    boolean isConfigured();

    /** Sends the message. True when the provider accepted it. */
    boolean send(EmailMessage message);
}
