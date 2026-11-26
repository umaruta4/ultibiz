package com.ultibiz.core.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The folder rules. */
class FolderPolicyTest {

    @Test
    void aFolderCannotBecomeItsOwnAncestor() {
        List<FolderPolicy.Node> all = List.of(
                new FolderPolicy.Node(1L, null, "ROOT"),
                new FolderPolicy.Node(2L, 1L, "OPS"),
                new FolderPolicy.Node(3L, 2L, "RUNBOOK"));
        // moving RUNBOOK under the root is fine
        assertDoesNotThrow(() -> FolderPolicy.checkMove(
                new FolderPolicy.Node(3L, 1L, "RUNBOOK"), all));
        // RUNBOOK under itself is refused
        assertThrows(KnowledgeException.class, () -> FolderPolicy.checkMove(
                new FolderPolicy.Node(3L, 3L, "RUNBOOK"), all));
        // OPS under RUNBOOK is refused: RUNBOOK is a child of OPS, so OPS would
        // become its own ancestor
        assertThrows(KnowledgeException.class, () -> FolderPolicy.checkMove(
                new FolderPolicy.Node(2L, 3L, "OPS"), all));
    }
}
