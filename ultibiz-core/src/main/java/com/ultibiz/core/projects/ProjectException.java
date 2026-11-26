package com.ultibiz.core.projects;

/** A broken project rule: the message is safe to show to the user. */
public class ProjectException extends RuntimeException {
    public ProjectException(String message) {
        super(message);
    }
}
