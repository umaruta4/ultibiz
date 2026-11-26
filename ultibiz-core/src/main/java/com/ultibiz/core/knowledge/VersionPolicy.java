package com.ultibiz.core.knowledge;

/** The versioning rules: the versions of a document count up without a gap. */
public final class VersionPolicy {

    private VersionPolicy() {
    }

    /** The next version number. */
    public static int nextVersion(int currentVersion) {
        if (currentVersion < 1) {
            throw new KnowledgeException("The current version must be at least 1");
        }
        return currentVersion + 1;
    }

    /** A version needs a title and content. */
    public static void checkContent(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new KnowledgeException("A document version needs a title");
        }
        if (content == null || content.isBlank()) {
            throw new KnowledgeException("A document version needs content");
        }
    }
}
