package com.ultibiz.core.knowledge;

/** A broken knowledge vault rule: the message is safe to show to the user. */
public class KnowledgeException extends RuntimeException {
    public KnowledgeException(String message) {
        super(message);
    }
}
