package com.ultibiz.core.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The versioning rules. */
class VersionPolicyTest {

    @Test
    void theVersionsCountUp() {
        assertEquals(2, VersionPolicy.nextVersion(1));
        assertEquals(7, VersionPolicy.nextVersion(6));
        assertThrows(KnowledgeException.class, () -> VersionPolicy.nextVersion(0));
    }

    @Test
    void aVersionNeedsATitleAndContent() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> VersionPolicy.checkContent("Handbook", "content"));
        assertThrows(KnowledgeException.class, () -> VersionPolicy.checkContent(" ", "content"));
        assertThrows(KnowledgeException.class, () -> VersionPolicy.checkContent("Handbook", " "));
    }
}
