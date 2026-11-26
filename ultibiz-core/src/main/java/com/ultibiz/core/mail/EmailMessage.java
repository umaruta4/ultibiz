package com.ultibiz.core.mail;

import java.nio.file.Path;

/** One outgoing e-mail with an optional attachment. */
public record EmailMessage(String to, String subject, String text,
                           Path attachment, String attachmentName) {

    public EmailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("The recipient is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("The subject is required");
        }
    }
}
