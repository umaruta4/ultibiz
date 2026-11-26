package com.ultibiz.core.knowledge;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The retention rules. */
class RetentionPolicyTest {

    @Test
    void aDocumentExpiresAfterItsPeriod() {
        LocalDate today = LocalDate.parse("2026-11-26");
        assertFalse(RetentionPolicy.expired(LocalDate.parse("2026-10-01"), 12, today),
                "a young document stays");
        assertTrue(RetentionPolicy.expired(LocalDate.parse("2025-01-01"), 12, today),
                "an old document expires");
        assertTrue(RetentionPolicy.expired(LocalDate.parse("2026-10-01"), 0, today),
                "a policy without a period expires everything");
        assertFalse(RetentionPolicy.expired(null, 12, today));
    }

    @Test
    void anUnknownActionIsRefused() {
        assertThrows(KnowledgeException.class, () -> RetentionPolicy.action("BURN"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> RetentionPolicy.action("DELETE"));
    }
}
